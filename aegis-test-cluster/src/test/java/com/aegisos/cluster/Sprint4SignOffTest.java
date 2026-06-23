package com.aegisos.cluster;

import com.aegisos.core.util.HexUtil;
import com.aegisos.fs.audit.AuditReportStore;
import com.aegisos.fs.audit.RepairRecommendation;
import com.aegisos.fs.audit.StorageAuditScheduler;
import com.aegisos.fs.audit.VerificationResult;
import com.aegisos.fs.audit.VerificationStatus;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 4 sign-off integration test.
 *
 * Four phases:
 * <ol>
 *   <li>Healthy cluster → no divergence, no verification, no recommendation</li>
 *   <li>Delete replica, first scan → divergence, INSUFFICIENT_HISTORY, no recommendation</li>
 *   <li>Second scan → VERIFIED, recommendation with evidence chain</li>
 *   <li>Restore replica → everything disappears, but history retained</li>
 * </ol>
 *
 * Asserts: no Raft command proposed, no metadata mutated, no chunk copied.
 */
public class Sprint4SignOffTest {

    private final ClusterHarness harness = new ClusterHarness();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void testFullVerificationRecommendationPipeline() throws Exception {
        // Boot 3-node cluster
        harness.setJobSupervisorEnabled(false); // Storage audit test: disable execution subsystem
        harness.setRepairEnabled(false); // Storage audit test: disable automatic repair (Raft mutations)
        List<AegisNode> nodes = harness.start(3);
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> n.discovery().membership().alivePeerIds().size() == 2));

        AegisNode leader = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            for (AegisNode n : nodes) {
                if (n.consensus().isLeader()) {
                    leader = n;
                    break;
                }
            }
            if (leader != null) break;
            Thread.sleep(50);
        }
        assertNotNull(leader, "Must have a leader");
        final AegisNode theLeader = leader;

        StorageAuditScheduler auditScheduler = leader.auditScheduler();

        // Write file with RF=3
        byte[] data = "sprint4-test-data".getBytes();
        leader.fileSystem().write("sprint4test.txt", data);

        // Wait until all 3 nodes have the chunk
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> !n.fileSystem().chunkStore().listChunkIds().isEmpty()));

        String chunkIdHex = nodes.get(0).fileSystem().chunkStore().listChunkIds().get(0);
        System.out.println("Chunk ID: " + chunkIdHex);

        // Capture Raft commit index before any audit operations
        long commitIndexBefore = leader.consensus().raftNode().commitIndex();

        // ===== PHASE 1: Healthy cluster =====
        System.out.println("\n=== PHASE 1: Healthy cluster ===");
        auditScheduler.runOnce();

        assertTrue(auditScheduler.getVerifications().isEmpty(),
                "Phase 1: no verifications expected on healthy cluster");
        assertTrue(auditScheduler.getRecommendations().isEmpty(),
                "Phase 1: no recommendations expected on healthy cluster");

        // Verify via HTTP
        String verificationsJson = fetchEndpoint(theLeader, "/audit/verifications");
        assertEquals("[\n]", verificationsJson.trim(),
                "Phase 1: /audit/verifications should be empty");

        String recommendationsJson = fetchEndpoint(theLeader, "/audit/recommendations");
        assertEquals("[\n]", recommendationsJson.trim(),
                "Phase 1: /audit/recommendations should be empty");

        System.out.println("Phase 1 PASSED: healthy cluster, no divergences");

        // ===== PHASE 2: Delete replica, first scan =====
        System.out.println("\n=== PHASE 2: Delete replica, first scan ===");

        // Find a follower and delete the chunk physically
        AegisNode follower = nodes.stream()
                .filter(n -> !n.identity().nodeId().equals(theLeader.identity().nodeId()))
                .findFirst().get();

        byte[] chunkId = HexUtil.decode(chunkIdHex);
        byte[] chunkBackup = follower.fileSystem().chunkStore().get(chunkId);
        assertNotNull(chunkBackup, "Chunk must exist on follower before deletion");

        follower.fileSystem().chunkStore().delete(chunkId);
        assertNull(follower.fileSystem().chunkStore().get(chunkId),
                "Chunk must be deleted from follower");

        // First audit scan after deletion
        auditScheduler.runOnce();

        List<VerificationResult> verifications = auditScheduler.getVerifications();
        assertFalse(verifications.isEmpty(),
                "Phase 2: should have at least one verification");

        VerificationResult v = verifications.stream()
                .filter(vr -> vr.chunkId().equals(chunkIdHex))
                .findFirst().orElseThrow(() -> new AssertionError("No verification for our chunk"));

        assertEquals(VerificationStatus.INSUFFICIENT_HISTORY, v.status(),
                "Phase 2: first scan should be INSUFFICIENT_HISTORY");

        assertTrue(auditScheduler.getRecommendations().isEmpty(),
                "Phase 2: no recommendation on first divergence scan");

        System.out.println("Phase 2 PASSED: INSUFFICIENT_HISTORY, no recommendation");

        // ===== PHASE 3: Second scan, VERIFIED =====
        System.out.println("\n=== PHASE 3: Second scan, verified ===");

        auditScheduler.runOnce();

        verifications = auditScheduler.getVerifications();
        v = verifications.stream()
                .filter(vr -> vr.chunkId().equals(chunkIdHex))
                .findFirst().orElseThrow(() -> new AssertionError("No verification for our chunk"));

        assertEquals(VerificationStatus.VERIFIED, v.status(),
                "Phase 3: second consecutive scan should verify");
        assertTrue(v.scanIdVerifiedAgainst() > 0,
                "Phase 3: scanIdVerifiedAgainst must be set");
        assertFalse(v.evidenceScans().isEmpty(),
                "Phase 3: evidence scans must not be empty");
        assertEquals(2, v.evidenceScans().size(),
                "Phase 3: evidence chain should reference 2 scan IDs");

        System.out.println("Phase 3: Verification evidence scans: " + v.evidenceScans());
        System.out.println("Phase 3: scanIdVerifiedAgainst: " + v.scanIdVerifiedAgainst());

        // Check recommendation
        List<RepairRecommendation> recommendations = auditScheduler.getRecommendations();
        assertFalse(recommendations.isEmpty(),
                "Phase 3: should have at least one recommendation");

        RepairRecommendation rec = recommendations.stream()
                .filter(r -> r.chunkId().equals(chunkIdHex))
                .findFirst().orElseThrow(() -> new AssertionError("No recommendation for our chunk"));

        assertEquals("UNDER_REPLICATED", rec.divergenceType());
        assertEquals(v.evidenceScans(), rec.evidenceScans(),
                "Phase 3: recommendation evidence should match verification evidence");
        assertTrue(rec.recommendedAt() > 0);

        // Verify via HTTP
        verificationsJson = fetchEndpoint(theLeader, "/audit/verifications");
        assertTrue(verificationsJson.contains("VERIFIED"),
                "Phase 3: HTTP /audit/verifications should contain VERIFIED");
        assertTrue(verificationsJson.contains(chunkIdHex),
                "Phase 3: HTTP response should contain our chunk ID");

        recommendationsJson = fetchEndpoint(theLeader, "/audit/recommendations");
        assertTrue(recommendationsJson.contains(chunkIdHex),
                "Phase 3: HTTP /audit/recommendations should contain our chunk");
        assertTrue(recommendationsJson.contains("UNDER_REPLICATED"),
                "Phase 3: HTTP response should contain divergence type");

        // Critical assertion: NO Raft mutations occurred
        long commitIndexAfter = theLeader.consensus().raftNode().commitIndex();
        assertEquals(commitIndexBefore, commitIndexAfter,
                "Phase 3: no Raft commands should have been proposed during audit/verification");

        // Verify chunk was NOT copied to any node
        for (AegisNode node : nodes) {
            if (node.identity().nodeId().equals(follower.identity().nodeId())) {
                assertNull(node.fileSystem().chunkStore().get(chunkId),
                        "Phase 3: deleted chunk must NOT have been restored by audit");
            }
        }

        System.out.println("Phase 3 PASSED: VERIFIED, recommendation exists, no Raft mutation, no chunk copy");

        // ===== PHASE 4: Restore replica, everything disappears =====
        System.out.println("\n=== PHASE 4: Restore replica ===");

        // Record how many scans are in the store before restore
        int scanCountBefore = auditScheduler.getStore().size();
        assertTrue(scanCountBefore >= 3,
                "Phase 4: should have at least 3 scans in history before restore");

        // Restore the chunk
        follower.fileSystem().chunkStore().put(chunkId, chunkBackup);
        assertNotNull(follower.fileSystem().chunkStore().get(chunkId),
                "Chunk must be restored on follower");

        // Run audit again
        auditScheduler.runOnce();

        // Verifications should be empty
        assertTrue(auditScheduler.getVerifications().isEmpty(),
                "Phase 4: verifications should be empty after restore");

        // Recommendations should be empty
        assertTrue(auditScheduler.getRecommendations().isEmpty(),
                "Phase 4: recommendations should be empty after restore");

        // Verify via HTTP
        verificationsJson = fetchEndpoint(theLeader, "/audit/verifications");
        assertEquals("[\n]", verificationsJson.trim(),
                "Phase 4: HTTP /audit/verifications should be empty");

        recommendationsJson = fetchEndpoint(theLeader, "/audit/recommendations");
        assertEquals("[\n]", recommendationsJson.trim(),
                "Phase 4: HTTP /audit/recommendations should be empty");

        // CRITICAL: History must be retained (not erased)
        AuditReportStore store = auditScheduler.getStore();
        assertTrue(store.size() > scanCountBefore,
                "Phase 4: history should have grown (scan added), not been erased");

        // The most recent scan should have no divergences
        var recentScans = store.recentScans();
        var lastScan = recentScans.get(recentScans.size() - 1);
        assertTrue(lastScan.divergences().isEmpty(),
                "Phase 4: most recent scan should show no divergences");

        // But earlier scans should still contain the divergence history
        boolean foundHistoricalDivergence = false;
        for (var scan : recentScans) {
            if (scan.containsChunk(chunkIdHex)) {
                foundHistoricalDivergence = true;
                break;
            }
        }
        assertTrue(foundHistoricalDivergence,
                "Phase 4: historical scans should still contain the divergence record");

        System.out.println("Phase 4 PASSED: recommendations empty, history retained");
        System.out.println("\n=== Sprint 4 Sign-Off Test PASSED ===");
    }

    private String fetchEndpoint(AegisNode node, String path) throws Exception {
        int port = node.metricsServer().boundPort();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}

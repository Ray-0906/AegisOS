package com.aegisos.cluster;

import com.aegisos.core.util.HexUtil;
import com.aegisos.fs.audit.*;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link StorageVerifier} edge cases in a live cluster.
 *
 * Covers:
 * - INSUFFICIENT_HISTORY when < 2 consecutive scans
 * - VERIFIED when all checks pass with correct evidence
 * - NO_LONGER_DIVERGENT when chunk heals between history and re-observation
 */
public class StorageVerificationTest {

    private final ClusterHarness harness = new ClusterHarness();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void testInsufficientHistoryOnFirstScan() throws Exception {
        List<AegisNode> nodes = harness.start(3);
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> n.discovery().membership().alivePeerIds().size() == 2));

        AegisNode leader = findLeader(nodes);
        StorageAuditScheduler scheduler = leader.auditScheduler();

        // Write file
        leader.fileSystem().write("test-insufficient.txt", "data".getBytes());
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> !n.fileSystem().chunkStore().listChunkIds().isEmpty()));

        String chunkIdHex = nodes.get(0).fileSystem().chunkStore().listChunkIds().get(0);

        // Delete from a follower
        AegisNode follower = nodes.stream()
                .filter(n -> !n.identity().nodeId().equals(leader.identity().nodeId()))
                .findFirst().get();
        follower.fileSystem().chunkStore().delete(HexUtil.decode(chunkIdHex));

        // Single scan
        scheduler.runOnce();

        List<VerificationResult> results = scheduler.getVerifications();
        VerificationResult v = results.stream()
                .filter(r -> r.chunkId().equals(chunkIdHex))
                .findFirst().orElseThrow();

        assertEquals(VerificationStatus.INSUFFICIENT_HISTORY, v.status());
        assertTrue(v.evidenceScans().isEmpty(),
                "No evidence chain on INSUFFICIENT_HISTORY");
        assertTrue(scheduler.getRecommendations().isEmpty());
    }

    @Test
    void testVerifiedWithCorrectEvidence() throws Exception {
        List<AegisNode> nodes = harness.start(3);
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> n.discovery().membership().alivePeerIds().size() == 2));

        AegisNode leader = findLeader(nodes);
        StorageAuditScheduler scheduler = leader.auditScheduler();

        leader.fileSystem().write("test-verified.txt", "data".getBytes());
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> !n.fileSystem().chunkStore().listChunkIds().isEmpty()));

        String chunkIdHex = nodes.get(0).fileSystem().chunkStore().listChunkIds().get(0);

        AegisNode follower = nodes.stream()
                .filter(n -> !n.identity().nodeId().equals(leader.identity().nodeId()))
                .findFirst().get();
        follower.fileSystem().chunkStore().delete(HexUtil.decode(chunkIdHex));

        // Two consecutive scans
        scheduler.runOnce();
        scheduler.runOnce();

        List<VerificationResult> results = scheduler.getVerifications();
        VerificationResult v = results.stream()
                .filter(r -> r.chunkId().equals(chunkIdHex))
                .findFirst().orElseThrow();

        assertEquals(VerificationStatus.VERIFIED, v.status());
        assertEquals(2, v.evidenceScans().size(),
                "Evidence chain should have exactly 2 scan IDs");
        assertTrue(v.evidenceScans().get(0) < v.evidenceScans().get(1),
                "Evidence scans should be in chronological order");
        assertTrue(v.scanIdVerifiedAgainst() > 0);

        // Recommendation should exist
        List<RepairRecommendation> recs = scheduler.getRecommendations();
        assertEquals(1, recs.size());
        assertEquals(chunkIdHex, recs.get(0).chunkId());
        assertEquals("UNDER_REPLICATED", recs.get(0).divergenceType());
        assertEquals(v.evidenceScans(), recs.get(0).evidenceScans());
    }

    @Test
    void testNoLongerDivergentAfterHeal() throws Exception {
        List<AegisNode> nodes = harness.start(3);
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> n.discovery().membership().alivePeerIds().size() == 2));

        AegisNode leader = findLeader(nodes);
        StorageAuditScheduler scheduler = leader.auditScheduler();

        leader.fileSystem().write("test-heal.txt", "data".getBytes());
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> !n.fileSystem().chunkStore().listChunkIds().isEmpty()));

        String chunkIdHex = nodes.get(0).fileSystem().chunkStore().listChunkIds().get(0);

        AegisNode follower = nodes.stream()
                .filter(n -> !n.identity().nodeId().equals(leader.identity().nodeId()))
                .findFirst().get();
        byte[] chunkId = HexUtil.decode(chunkIdHex);
        byte[] backup = follower.fileSystem().chunkStore().get(chunkId);
        follower.fileSystem().chunkStore().delete(chunkId);

        // First scan: divergence recorded
        scheduler.runOnce();
        assertEquals(VerificationStatus.INSUFFICIENT_HISTORY,
                scheduler.getVerifications().stream()
                        .filter(v -> v.chunkId().equals(chunkIdHex))
                        .findFirst().orElseThrow().status());

        // Heal before second scan
        follower.fileSystem().chunkStore().put(chunkId, backup);

        // Second scan: history says persisted, but re-observation finds it healed
        scheduler.runOnce();

        // The chunk should no longer appear as divergent at all
        // (since re-observation in the scan itself will not detect it as under-replicated)
        assertTrue(scheduler.getVerifications().isEmpty() ||
                        scheduler.getVerifications().stream()
                                .noneMatch(v -> v.chunkId().equals(chunkIdHex)
                                        && v.status() == VerificationStatus.VERIFIED),
                "Healed chunk must not be VERIFIED");

        assertTrue(scheduler.getRecommendations().isEmpty(),
                "No recommendation for healed chunk");
    }

    @Test
    void testPersistentRecommendationAcrossMultipleScans() throws Exception {
        List<AegisNode> nodes = harness.start(3);
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> n.discovery().membership().alivePeerIds().size() == 2));

        AegisNode leader = findLeader(nodes);
        StorageAuditScheduler scheduler = leader.auditScheduler();

        leader.fileSystem().write("test-persistent.txt", "data".getBytes());
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> !n.fileSystem().chunkStore().listChunkIds().isEmpty()));

        String chunkIdHex = nodes.get(0).fileSystem().chunkStore().listChunkIds().get(0);

        AegisNode follower = nodes.stream()
                .filter(n -> !n.identity().nodeId().equals(leader.identity().nodeId()))
                .findFirst().get();
        follower.fileSystem().chunkStore().delete(HexUtil.decode(chunkIdHex));

        // Scan 1
        scheduler.runOnce();
        assertEquals(VerificationStatus.INSUFFICIENT_HISTORY,
                scheduler.getVerifications().get(0).status());
        assertTrue(scheduler.getRecommendations().isEmpty());

        // Scan 2
        scheduler.runOnce();
        assertEquals(VerificationStatus.VERIFIED,
                scheduler.getVerifications().get(0).status());
        assertEquals(1, scheduler.getRecommendations().size());
        assertEquals(List.of(1L, 2L), scheduler.getRecommendations().get(0).evidenceScans());

        // Scan 3
        scheduler.runOnce();
        assertEquals(VerificationStatus.VERIFIED,
                scheduler.getVerifications().get(0).status());
        assertEquals(1, scheduler.getRecommendations().size());
        assertEquals(List.of(2L, 3L), scheduler.getRecommendations().get(0).evidenceScans());

        // Scan 4
        scheduler.runOnce();
        assertEquals(VerificationStatus.VERIFIED,
                scheduler.getVerifications().get(0).status());
        assertEquals(1, scheduler.getRecommendations().size());
        assertEquals(List.of(3L, 4L), scheduler.getRecommendations().get(0).evidenceScans());

        // Scan 5
        scheduler.runOnce();
        assertEquals(VerificationStatus.VERIFIED,
                scheduler.getVerifications().get(0).status());
        assertEquals(1, scheduler.getRecommendations().size());
        assertEquals(List.of(4L, 5L), scheduler.getRecommendations().get(0).evidenceScans());

        // Assert only one recommendation is generated for this chunk
        assertEquals(1, scheduler.getRecommendations().size());
    }

    private AegisNode findLeader(List<AegisNode> nodes) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            for (AegisNode n : nodes) {
                if (n.consensus().isLeader()) return n;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("No leader found");
    }
}

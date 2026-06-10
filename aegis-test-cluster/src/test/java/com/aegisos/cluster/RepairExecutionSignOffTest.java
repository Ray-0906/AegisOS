package com.aegisos.cluster;

import com.aegisos.core.util.HexUtil;
import com.aegisos.fs.audit.RepairOutcome;
import com.aegisos.fs.audit.RepairTaskStore;
import com.aegisos.fs.audit.StorageAuditScheduler;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.StateCommand;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class RepairExecutionSignOffTest {

    private final ClusterHarness harness = new ClusterHarness();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void testRepairExecutionSignOff() throws Exception {
        // ===== Phase 1 — Baseline =====
        System.out.println("=== Phase 1: Setup and Baseline ===");
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
        assertNotNull(leader, "Must have elected a leader A");
        final AegisNode A = leader;

        // Write file with RF=3
        byte[] data = "sprint5-signoff-data".getBytes();
        byte[] fileId = A.fileSystem().write("signoff.txt", data);
        assertNotNull(fileId);

        // Wait until all 3 nodes have the chunk
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> !n.fileSystem().chunkStore().listChunkIds().isEmpty()));

        String chunkIdHex = A.fileSystem().chunkStore().listChunkIds().get(0);
        byte[] chunkId = HexUtil.decode(chunkIdHex);

        StorageAuditScheduler auditScheduler = A.auditScheduler();

        // Run runOnce() on leader
        auditScheduler.runOnce();

        // Assert: no divergence, no recommendation, no repair
        assertTrue(auditScheduler.getVerifications().isEmpty(), "Phase 1: No verifications expected");
        assertTrue(auditScheduler.getRecommendations().isEmpty(), "Phase 1: No recommendations expected");
        assertTrue(auditScheduler.getRepairOutcomes().isEmpty(), "Phase 1: No repair outcomes expected");
        assertTrue(A.fileSystem().repairTaskStore().all().isEmpty(), "Phase 1: No repair tasks expected");

        System.out.println("Phase 1 PASSED");

        // ===== Phase 2 — Induce Under-Replication =====
        System.out.println("=== Phase 2: Induce Under-Replication ===");
        // Find follower node and delete the chunk replica
        AegisNode C = nodes.stream()
                .filter(n -> !n.identity().nodeId().equals(A.identity().nodeId()))
                .findFirst().get();
        C.fileSystem().chunkStore().delete(chunkId);
        assertNull(C.fileSystem().chunkStore().get(chunkId), "Chunk deleted from C");

        // Run runOnce() — first scan
        auditScheduler.runOnce();

        // Assert: divergence detected, INSUFFICIENT_HISTORY, no repair proposed
        assertFalse(auditScheduler.getVerifications().isEmpty(), "Phase 2: Divergence should be registered");
        assertTrue(auditScheduler.getRecommendations().isEmpty(), "Phase 2: Recommendation should not be generated yet");
        assertTrue(auditScheduler.getRepairOutcomes().isEmpty(), "Phase 2: No repair outcomes should be proposed yet");
        assertTrue(A.fileSystem().repairTaskStore().all().isEmpty(), "Phase 2: Repair task store should be empty");

        System.out.println("Phase 2 PASSED");

        // ===== Phase 3 — Verification =====
        System.out.println("=== Phase 3: Verification ===");
        // Assert under-replicated in metadata is false since C is still in metadata
        assertFalse(A.fileSystem().isStillUnderReplicated(chunkId, fileId), "Phase 3: FileIndex metadata lists all nodes, so not under-replicated in metadata");

        // Run runOnce() — second scan
        auditScheduler.runOnce();

        // Assert: VERIFIED recommendation exists
        assertFalse(auditScheduler.getRecommendations().isEmpty(), "Phase 3: Verified recommendation must exist");
        assertEquals(1, auditScheduler.getRecommendations().size());

        // Wait! The runOnce() also proposed the repair because proposer is wired in!
        // So Phase 4 assertions can be done here.
        System.out.println("Phase 3 PASSED");

        // ===== Phase 4 — Two-Phase Repair =====
        System.out.println("=== Phase 4: Two-Phase Repair ===");
        // Check repair outcomes
        List<RepairOutcome> outcomes = auditScheduler.getRepairOutcomes();
        assertFalse(outcomes.isEmpty(), "Phase 4: Repair outcomes should exist");

        // We expect REPAIR_PROPOSED and COPY_SUCCEEDED in the outcomes
        boolean proposed = outcomes.stream().anyMatch(o -> o.status() == RepairOutcome.Status.REPAIR_PROPOSED);
        boolean copySucceeded = outcomes.stream().anyMatch(o -> o.status() == RepairOutcome.Status.COPY_SUCCEEDED);

        assertTrue(proposed, "Phase 4: REPAIR_CHUNK should have been proposed");
        assertTrue(copySucceeded, "Phase 4: Physical copy and REPAIR_COMPLETE should succeed");

        String repairId = outcomes.stream()
                .filter(o -> o.repairId() != null)
                .map(RepairOutcome::repairId)
                .findFirst().get();

        // Assert: RepairTaskStore has task as COMPLETE
        List<RepairTaskStore.RepairTask> tasks = A.fileSystem().repairTaskStore().all();
        assertFalse(tasks.isEmpty(), "Phase 4: Repair task should exist");
        RepairTaskStore.RepairTask task = tasks.get(0);
        assertEquals(repairId, task.repairId());
        assertEquals(RepairTaskStore.TaskStatus.COMPLETE, task.status(), "Phase 4: Task status must be COMPLETE");

        // Assert: Physical chunk now exists on C
        assertNotNull(C.fileSystem().chunkStore().get(chunkId), "Phase 4: Physical replica must have been copied to node C");

        // Assert: FileIndex shows chunk on C
        assertTrue(A.fileSystem().isStillUnderReplicated(chunkId, fileId) == false, "Phase 4: FileIndex must show chunk is fully replicated");

        System.out.println("Phase 4 PASSED");

        // ===== Phase 5 — Post-Repair Verification =====
        System.out.println("=== Phase 5: Post-Repair Verification ===");
        // Run runOnce() — third scan
        auditScheduler.runOnce();

        // Assert: no divergence, no recommendation, no repair proposed
        assertTrue(auditScheduler.getVerifications().isEmpty(), "Phase 5: No verifications");
        assertTrue(auditScheduler.getRecommendations().isEmpty(), "Phase 5: No recommendations");
        assertTrue(auditScheduler.getRepairOutcomes().isEmpty(), "Phase 5: No repair outcomes proposed");

        System.out.println("Phase 5 PASSED");

        // ===== Phase 6 — Idempotency =====
        System.out.println("=== Phase 6: Idempotency ===");
        // Re-propose the same REPAIR_COMPLETE manually
        com.aegisos.proto.RepairComplete manualComplete = com.aegisos.proto.RepairComplete.newBuilder()
                .setRepairId(repairId)
                .setFileId(ByteString.copyFrom(fileId))
                .setChunkId(ByteString.copyFrom(chunkId))
                .setTargetNodeId(ByteString.copyFrom(C.identity().nodeId().toBytes()))
                .setSourceNodeId(ByteString.copyFrom(A.identity().nodeId().toBytes()))
                .build();

        StateCommand cmd = StateCommand.newBuilder()
                .setType(CommandType.REPAIR_COMPLETE)
                .setPayload(manualComplete.toByteString())
                .build();

        // Proposing manually should be accepted by state machine, but treated as no-op without duplicating replica
        A.consensus().propose(cmd).get();

        // Check if task status stays COMPLETE
        assertEquals(RepairTaskStore.TaskStatus.COMPLETE, A.fileSystem().repairTaskStore().all().get(0).status());
        System.out.println("Phase 6 PASSED");

        // ===== Phase 7 — Follower Verification =====
        System.out.println("=== Phase 7: Follower Verification ===");
        // Find follower node B
        AegisNode B = nodes.stream()
                .filter(n -> !n.identity().nodeId().equals(A.identity().nodeId()) && !n.identity().nodeId().equals(C.identity().nodeId()))
                .findFirst().get();

        // Verify: FileIndex reflects metadata change
        assertFalse(B.fileSystem().isStillUnderReplicated(chunkId, fileId), "Follower B FileIndex must show chunk fully replicated");

        // Verify: RepairTaskStore shows task as COMPLETE
        Optional<RepairTaskStore.RepairTask> followerTask = B.fileSystem().repairTaskStore().pendingByRepairId(repairId);
        // Wait, pendingByRepairId only returns PENDING tasks! Let's check all().
        assertTrue(B.fileSystem().repairTaskStore().all().stream().anyMatch(t -> t.repairId().equals(repairId) && t.status() == RepairTaskStore.TaskStatus.COMPLETE),
                "Follower B task store must show task as COMPLETE");

        // Verify: NO physical copy was triggered on B (meaning B doesn't have the chunk replica if it was deleted)
        // B was never deleted, but A proposed copy to C. Check that B has only its own chunk, and did not run audit.
        assertTrue(B.auditScheduler().getVerifications().isEmpty(), "Follower B audit scheduler should not have run scan");

        // Verify via REST endpoints
        String repairsJson = fetchEndpoint(A, "/audit/repairs");
        assertTrue(repairsJson.contains("COPY_SUCCEEDED"));

        String tasksJson = fetchEndpoint(A, "/audit/tasks");
        assertTrue(tasksJson.contains("COMPLETE"));

        System.out.println("Phase 7 PASSED");
        System.out.println("=== RepairExecutionSignOffTest PASSED ===");
    }

    private String fetchEndpoint(AegisNode node, String path) throws Exception {
        int port = node.metrics().boundPort();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}

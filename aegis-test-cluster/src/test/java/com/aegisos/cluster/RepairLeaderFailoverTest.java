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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class RepairLeaderFailoverTest {

    private final ClusterHarness harness = new ClusterHarness();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void testLeaderFailoverDuringRepair() throws Exception {
        // ===== Phase 1 — Setup and Divergence =====
        System.out.println("=== Phase 1: Setup and Divergence ===");
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
        final AegisNode A = leader;

        // Write file with RF=3
        byte[] data = "failover-test-data".getBytes();
        byte[] fileId = A.fileSystem().write("failover.txt", data);
        assertNotNull(fileId);

        // Wait until all 3 nodes have the chunk
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> !n.fileSystem().chunkStore().listChunkIds().isEmpty()));

        String chunkIdHex = A.fileSystem().chunkStore().listChunkIds().get(0);
        byte[] chunkId = HexUtil.decode(chunkIdHex);

        // Delete replica on node C
        AegisNode C = nodes.stream()
                .filter(n -> !n.identity().nodeId().equals(A.identity().nodeId()))
                .findFirst().get();
        C.fileSystem().chunkStore().delete(chunkId);
        assertNull(C.fileSystem().chunkStore().get(chunkId), "Chunk deleted from C");

        // Run 2x runOnce() on A to verify without automatic repair proposal
        A.auditScheduler().runOnce(); // first scan
        A.auditScheduler().setRepairProposer(null); // disable proposer on A
        A.auditScheduler().runOnce(); // second scan -> verified recommendation exists
        
        assertEquals(1, A.auditScheduler().getRecommendations().size(), "Recommendation should exist on A");

        System.out.println("Phase 1 PASSED");

        // ===== Phase 2 — Phase A Commits, Leader Dies =====
        System.out.println("=== Phase 2: Phase A Commits, Leader Dies ===");

        // We propose REPAIR_CHUNK manually to A so it commits on the cluster but does NOT trigger automatic copy
        String repairId = UUID.randomUUID().toString();
        com.aegisos.proto.RepairChunk repairChunkCmd = com.aegisos.proto.RepairChunk.newBuilder()
                .setRepairId(repairId)
                .setChunkId(ByteString.copyFrom(chunkId))
                .addEvidenceScans(1L)
                .addEvidenceScans(2L)
                .setVerifiedAt(System.currentTimeMillis())
                .build();

        StateCommand stateCmd = StateCommand.newBuilder()
                .setType(CommandType.REPAIR_CHUNK)
                .setPayload(repairChunkCmd.toByteString())
                .build();

        A.consensus().propose(stateCmd).get();

        // Verify the task is created as PENDING in all nodes
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> n.fileSystem().repairTaskStore().pendingByRepairId(repairId).isPresent()));

        // Kill leader A before physical copy executes
        harness.stop(A);

        // Wait for leader B to be elected
        AegisNode B = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            for (AegisNode n : nodes) {
                if (!n.identity().nodeId().equals(A.identity().nodeId()) && n.consensus().isLeader()) {
                    B = n;
                    break;
                }
            }
            if (B != null) break;
            Thread.sleep(50);
        }
        assertNotNull(B, "Must elect a new leader B");
        final AegisNode newLeader = B;
        ClusterHarness.await(15000, () -> newLeader.discovery().membership().alivePeerIds().size() == 1);

        System.out.println("New leader B elected: " + newLeader.identity().nodeId().shortId());
        System.out.println("Phase 2 PASSED");

        // ===== Phase 3 — New Leader State Verification =====
        System.out.println("=== Phase 3: New Leader State Verification ===");

        // Assert: FileIndex unchanged on leader B (no REPAIR_COMPLETE applied, chunk not under-replicated in metadata)
        assertFalse(newLeader.fileSystem().isStillUnderReplicated(chunkId, fileId), "FileIndex must remain unchanged");

        // Assert: RepairTaskStore on leader B shows task as PENDING
        Optional<RepairTaskStore.RepairTask> taskOpt = newLeader.fileSystem().repairTaskStore().pendingByRepairId(repairId);
        assertTrue(taskOpt.isPresent(), "RepairTask must be present");
        assertEquals(RepairTaskStore.TaskStatus.PENDING, taskOpt.get().status(), "Task must be PENDING");

        // Assert: leader B does NOT automatically execute the physical copy
        newLeader.auditScheduler().runOnce();

        // Check B's outcomes
        List<RepairOutcome> outcomesB = newLeader.auditScheduler().getRepairOutcomes();
        boolean copyExecuted = outcomesB.stream().anyMatch(o -> o.status() == RepairOutcome.Status.COPY_SUCCEEDED);
        assertFalse(copyExecuted, "New leader must not execute copy for task proposed by old leader");
        assertNull(C.fileSystem().chunkStore().get(chunkId), "C must still not have the chunk");

        System.out.println("Phase 3 PASSED");

        // ===== Phase 4 — New Leader Re-Verifies and Completes =====
        System.out.println("=== Phase 4: New Leader Re-Verifies and Completes ===");

        // Wait for PENDING task to expire: we do this by backdating the task on newLeader B
        newLeader.fileSystem().repairTaskStore().pendingByRepairId(repairId).get()
                .setCommittedAt(System.currentTimeMillis() - 1000000L); // set in the past to expire

        // Run runOnce() on B — B's scheduler will run, expire the old task, verify the divergence,
        // and propose the new REPAIR_CHUNK. Physical copy may or may not succeed immediately.
        newLeader.auditScheduler().runOnce();

        // Assert Phase A: Leadership recovery and task proposal is immediate
        
        AegisNode currentLeader = nodes.stream()
                .filter(n -> n.consensus().isLeader())
                .findFirst().orElse(null);
        String currentLeaderId = (currentLeader != null) ? currentLeader.identity().nodeId().shortId() : "NONE";
        
        System.out.println("DEBUG: Phase A checks:");
        System.out.println("  newLeader (expected B): " + newLeader.identity().nodeId().shortId());
        System.out.println("  newLeader.isLeader(): " + newLeader.consensus().isLeader());
        System.out.println("  Actual cluster leader: " + currentLeaderId);
        
        assertTrue(newLeader.consensus().isLeader(), "Phase A: Expected B to still be the consensus leader");
        
        List<RepairOutcome> initialOutcomes = newLeader.auditScheduler().getRepairOutcomes();
        boolean proposedNew = initialOutcomes.stream().anyMatch(o -> o.status() == RepairOutcome.Status.REPAIR_PROPOSED);
        boolean blockedByPending = initialOutcomes.stream().anyMatch(o -> o.status() == RepairOutcome.Status.BLOCKED);

        if (blockedByPending) {
            // The background timer on a node might have proposed a repair between Phase 3 and Phase 4.
            // Verify that the blocking task is a newly generated task, and not the stale task from Phase 1.
            java.util.Optional<com.aegisos.fs.audit.RepairTaskStore.RepairTask> pendingTaskOpt = newLeader.fileSystem().repairTaskStore().pendingFor(chunkIdHex);
            assertTrue(pendingTaskOpt.isPresent(), "If blocked, a pending task must exist");
            com.aegisos.fs.audit.RepairTaskStore.RepairTask pendingTask = pendingTaskOpt.get();

            System.out.println("DEBUG: Blocked by pending task:");
            System.out.println("  repairId: " + pendingTask.repairId());
            System.out.println("  status: " + pendingTask.status());
            System.out.println("  committedAt: " + pendingTask.committedAt());

            assertNotEquals(repairId, pendingTask.repairId(), "The original task should have been expired; a new task should have been created after failover");

            // Verify the old task is expired
            java.util.Optional<com.aegisos.fs.audit.RepairTaskStore.RepairTask> oldTaskOpt = newLeader.fileSystem().repairTaskStore().all().stream()
                    .filter(t -> t.repairId().equals(repairId))
                    .findFirst();
            assertTrue(oldTaskOpt.isPresent() && oldTaskOpt.get().status() == com.aegisos.fs.audit.RepairTaskStore.TaskStatus.EXPIRED,
                    "The old task from Phase 1 must be EXPIRED");
        } else if (!proposedNew) {
            System.out.println("Phase A failed! initialOutcomes:");
            for (RepairOutcome outcome : initialOutcomes) {
                System.out.println("  - Status: " + outcome.status() + ", Reason: " + outcome.details());
            }
        }
        
        assertTrue(proposedNew || blockedByPending, "New leader must propose a new REPAIR_CHUNK immediately or be correctly blocked by a duplicate pending repair");

        // Assert Phase B: Physical copy and completion is eventual
        ClusterHarness.await(15000, () -> {
            newLeader.auditScheduler().runOnce();
            List<RepairOutcome> outcomes = newLeader.auditScheduler().getRepairOutcomes();
            return outcomes.stream().anyMatch(o -> o.status() == RepairOutcome.Status.COPY_SUCCEEDED);
        });

        // Assert: physical copy succeeds (C gets the chunk)
        assertNotNull(C.fileSystem().chunkStore().get(chunkId), "C must physically possess the chunk now");

        // Assert: FileIndex now correct
        assertFalse(newLeader.fileSystem().isStillUnderReplicated(chunkId, fileId), "FileIndex must show full replication");

        System.out.println("Phase 4 PASSED");

        System.out.println("=== RepairLeaderFailoverTest PASSED ===");
    }
}

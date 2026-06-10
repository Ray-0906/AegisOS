package com.aegisos.cluster;

import com.aegisos.core.util.HexUtil;
import com.aegisos.fs.audit.RepairOutcome;
import com.aegisos.fs.audit.RepairTaskStore;
import com.aegisos.fs.audit.StorageAuditScheduler;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.StateCommand;
import com.google.protobuf.ByteString;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RepairCopyFailureTest {

    private final ClusterHarness harness = new ClusterHarness();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void testRepairCopyFailureAndRecovery() throws Exception {
        // ===== Phase 1 — Setup =====
        System.out.println("=== Phase 1: Setup ===");
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
        byte[] data = "copy-failure-test".getBytes();
        byte[] fileId = A.fileSystem().write("copyfailure.txt", data);
        assertNotNull(fileId);

        // Wait until all 3 nodes have the chunk
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> !n.fileSystem().chunkStore().listChunkIds().isEmpty()));

        String chunkIdHex = A.fileSystem().chunkStore().listChunkIds().get(0);
        byte[] chunkId = HexUtil.decode(chunkIdHex);

        // Find follow node C and delete chunk replica
        AegisNode C = nodes.stream()
                .filter(n -> !n.identity().nodeId().equals(A.identity().nodeId()))
                .findFirst().get();
        
        byte[] chunkBackupC = C.fileSystem().chunkStore().get(chunkId);
        assertNotNull(chunkBackupC);
        C.fileSystem().chunkStore().delete(chunkId);

        // Run 2x runOnce() to generate recommendation and verify
        StorageAuditScheduler auditScheduler = A.auditScheduler();
        auditScheduler.runOnce(); // First scan: INSUFFICIENT_HISTORY

        // ===== Phase 2 — Force Copy Failure =====
        System.out.println("=== Phase 2: Force Copy Failure ===");

        // Delete the chunk from the other nodes (A and B) so no healthy source physically holds it
        AegisNode B = nodes.stream()
                .filter(n -> !n.identity().nodeId().equals(A.identity().nodeId()) && !n.identity().nodeId().equals(C.identity().nodeId()))
                .findFirst().get();

        byte[] chunkBackupA = A.fileSystem().chunkStore().get(chunkId);
        byte[] chunkBackupB = B.fileSystem().chunkStore().get(chunkId);

        assertNotNull(chunkBackupA);
        assertNotNull(chunkBackupB);

        A.fileSystem().chunkStore().delete(chunkId);
        B.fileSystem().chunkStore().delete(chunkId);

        // Run second scan. This will verify the recommendation and immediately run proposer
        auditScheduler.runOnce();

        // Check repair outcomes
        List<RepairOutcome> outcomes = auditScheduler.getRepairOutcomes();
        assertFalse(outcomes.isEmpty(), "Repair outcomes should exist");

        boolean proposed = outcomes.stream().anyMatch(o -> o.status() == RepairOutcome.Status.REPAIR_PROPOSED);
        assertTrue(proposed, "REPAIR_CHUNK must be proposed");

        // The copy should fail because there is no source node with chunk data
        boolean copyFailed = outcomes.stream().anyMatch(o -> o.status() == RepairOutcome.Status.COPY_FAILED || o.status() == RepairOutcome.Status.NO_SOURCE);
        assertTrue(copyFailed, "Copy must fail when no physical replica is found");

        // Assert: REPAIR_COMPLETE was NOT proposed
        boolean completeProposed = outcomes.stream().anyMatch(o -> o.status() == RepairOutcome.Status.COPY_SUCCEEDED);
        assertFalse(completeProposed, "REPAIR_COMPLETE must not be proposed on copy failure");

        // Assert: FileIndex unchanged (chunk is not under-replicated in metadata because C is in metadata)
        assertFalse(A.fileSystem().isStillUnderReplicated(chunkId, fileId), "FileIndex metadata must remain unchanged");

        // Assert: RepairTaskStore task is still PENDING
        List<RepairTaskStore.RepairTask> tasks = A.fileSystem().repairTaskStore().all();
        assertFalse(tasks.isEmpty());
        assertEquals(RepairTaskStore.TaskStatus.PENDING, tasks.get(0).status(), "Task status must remain PENDING");

        System.out.println("Phase 2 PASSED");

        // ===== Phase 3 — Verify Audit Consistency =====
        System.out.println("=== Phase 3: Verify Audit Consistency ===");
        // Run runOnce() again
        auditScheduler.runOnce();

        // Assert: audit still reports chunk as under-replicated (verification still happens)
        assertFalse(auditScheduler.getVerifications().isEmpty(), "Audit must still verify divergence");

        // Assert: task is still PENDING
        assertEquals(RepairTaskStore.TaskStatus.PENDING, A.fileSystem().repairTaskStore().all().get(0).status());

        System.out.println("Phase 3 PASSED");

        // ===== Phase 4 — Recovery =====
        System.out.println("=== Phase 4: Recovery ===");
        // Restore chunk replicas to A and B
        A.fileSystem().chunkStore().put(chunkId, chunkBackupA);
        B.fileSystem().chunkStore().put(chunkId, chunkBackupB);

        // Run audit scheduler runOnce() again
        auditScheduler.runOnce();

        // Assert: repair succeeds this time (complete proposed and committed)
        List<RepairOutcome> recoveryOutcomes = auditScheduler.getRepairOutcomes();
        boolean recoverySuccess = recoveryOutcomes.stream().anyMatch(o -> o.status() == RepairOutcome.Status.COPY_SUCCEEDED);
        assertTrue(recoverySuccess, "Repair must succeed after chunk data is restored");

        // Assert: FileIndex now correct
        assertFalse(A.fileSystem().isStillUnderReplicated(chunkId, fileId), "FileIndex must show full replication after recovery");

        // Assert: task is COMPLETE
        assertEquals(RepairTaskStore.TaskStatus.COMPLETE, A.fileSystem().repairTaskStore().all().get(0).status());

        System.out.println("Phase 4 PASSED");
        System.out.println("=== RepairCopyFailureTest PASSED ===");
    }
}

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
import com.aegisos.testing.ClusterAwaiter;

import static org.junit.jupiter.api.Assertions.*;

public class RepairLeaderFailoverTest {

    private final ClusterHarness harness = new ClusterHarness();

    @AfterEach
    void tearDown() {
        harness.close();
        System.clearProperty("aegis.audit.interval.seconds");
    }

    @Test
    void testLeaderFailoverDuringRepair() throws Exception {
        System.setProperty("aegis.audit.interval.seconds", "1"); // enable fast background scheduler ticks
        harness.setRepairTaskTimeoutSeconds(1); // 1s expiration for fast failover
        ClusterAwaiter awaiter = new ClusterAwaiter(harness);

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

        // Verify the task is created as PENDING on the leader
        awaiter.awaitPendingRepair(repairId, java.time.Duration.ofSeconds(5));

        // Kill leader A before physical copy executes
        long t0_leaderKilled = System.nanoTime();
        harness.stop(A);
        // Assert: new leader B elected
        awaiter.awaitLeaderElection(java.time.Duration.ofSeconds(10));
        AegisNode B = harness.currentLeader();
        assertNotNull(B, "Must elect a new leader B");

        System.out.println("New leader B elected: " + B.identity().nodeId().shortId());

        // Wait for B to notice and track the repair task
        awaiter.awaitRepairTaskVisible(repairId, java.time.Duration.ofSeconds(10));

        // The original task expires in 1s. B's background scheduler (running every 1s) will propose a new one and execute it.
        // We await the physical replication of the chunk, which proves the repair completed.
        ClusterHarness.await(25000, () -> 
            C.fileSystem().chunkStore().get(chunkId) != null &&
            !B.fileSystem().isStillUnderReplicated(chunkId, fileId)
        );

        // Assert: physical copy succeeds (C gets the chunk)
        assertNotNull(C.fileSystem().chunkStore().get(chunkId), "C must physically possess the chunk now");

        // Assert: FileIndex now correct
        assertFalse(B.fileSystem().isStillUnderReplicated(chunkId, fileId), "FileIndex must show full replication");

        System.out.println("Phase 4 PASSED");

        // Independently await gossip death detection
        awaiter.awaitNodeDeath(A.identity().nodeId(), java.time.Duration.ofSeconds(15));

        System.out.println("=== RepairLeaderFailoverTest PASSED ===");
    }
}

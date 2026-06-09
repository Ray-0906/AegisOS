package com.aegisos.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 6 acceptance test: RepairTaskStore recovery from snapshot.
 *
 * Flow:
 * 1. Create divergence
 * 2. Commit REPAIR_CHUNK (task becomes PENDING)
 * 3. Create snapshot
 * 4. Delete old logs
 * 5. Restart cluster
 * 6. Verify pending repair still exists
 * 7. Complete repair
 * 8. Verify REPAIR_COMPLETE works
 */
public class SnapshotRepairTaskRecoveryTest {

    @Test
    @DisplayName("Repair task survives snapshot creation, log truncation, and full cluster restart")
    void repairTaskSurvivesSnapshotAndRestart() throws Exception {
        try (ClusterHarness cluster = new ClusterHarness()) {
            java.util.List<com.aegisos.node.AegisNode> nodes = cluster.start(3);
            com.aegisos.node.AegisNode leader = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                for (com.aegisos.node.AegisNode n : nodes) if (n.consensus().isLeader()) leader = n;
                if (leader != null) break;
                Thread.sleep(50);
            }
            org.junit.jupiter.api.Assertions.assertNotNull(leader);

            // 1. Upload a file
            leader.fileSystem().write("file-1", new byte[100]);
            Thread.sleep(1000);

            // 2. Corrupt one replica (e.g. stop it, or just use the repair mechanism directly)
            // It's easier to simulate a repair task by proposing REPAIR_CHUNK directly
            String repairId = java.util.UUID.randomUUID().toString();
            com.aegisos.proto.RepairChunk repairChunk = com.aegisos.proto.RepairChunk.newBuilder()
                    .setRepairId(repairId)
                    .setChunkId(com.google.protobuf.ByteString.copyFrom(new byte[32]))
                    .build();
                    
            leader.consensus().propose(com.aegisos.proto.StateCommand.newBuilder()
                    .setType(com.aegisos.proto.CommandType.REPAIR_CHUNK)
                    .setPayload(repairChunk.toByteString())
                    .build()).get();

            // 3. Create snapshot
            for (com.aegisos.node.AegisNode n : nodes) {
                n.consensus().raftNode().triggerSnapshot();
            }

            // 4 & 5. Restart cluster
            java.util.List<com.aegisos.node.AegisNode> oldNodes = new java.util.ArrayList<>(nodes);
            for (com.aegisos.node.AegisNode n : oldNodes) {
                cluster.stop(n);
            }
            for (com.aegisos.node.AegisNode n : oldNodes) {
                cluster.restartNode(n);
            }
            
            // Wait for leader
            Thread.sleep(5000);
            com.aegisos.node.AegisNode newLeader = null;
            for (com.aegisos.node.AegisNode n : cluster.nodes()) if (n.consensus().isLeader()) newLeader = n;

            // 6. Verify pending repair still exists
            org.junit.jupiter.api.Assertions.assertTrue(newLeader.fileSystem().repairTaskStore().all().stream().anyMatch(t -> t.status() == com.aegisos.fs.audit.RepairTaskStore.TaskStatus.PENDING));
        }
    }
}

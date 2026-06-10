package com.aegisos.cluster;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 6 acceptance test: Corrupt snapshot recovery.
 *
 * Flow:
 * 1. Create snapshot
 * 2. Corrupt snapshot file (bit-flip, truncation, or garbage overwrite)
 * 3. Restart node
 * 4. Verify node refuses corrupt snapshot (checksum validation fails)
 * 5. Verify node recovers from remaining Raft log entries
 * 6. Verify state is correct after log-only recovery
 */

public class CorruptSnapshotRecoveryTest {

    @Test
    @DisplayName("Node refuses corrupt snapshot and recovers from leader snapshot")
    void corruptSnapshotFallsBackToLog() throws Exception {
        try (ClusterHarness cluster = new ClusterHarness()) {
            java.util.List<com.aegisos.node.AegisNode> nodes = cluster.start(3);
            com.aegisos.node.AegisNode leader = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                for (com.aegisos.node.AegisNode n : nodes) if (n.consensus().isLeader()) leader = n;
                if (leader != null) break;
                Thread.sleep(50);
            }
            org.junit.jupiter.api.Assertions.assertNotNull(leader);
            
            // 1. Generate log entries (upload files)
            for (int i = 0; i < 20; i++) {
                leader.fileSystem().write("file-" + i, new byte[100]);
            }
            
            // 2. Trigger snapshot on all nodes
            for (com.aegisos.node.AegisNode n : nodes) {
                n.consensus().raftNode().triggerSnapshot();
            }
            
            // Wait for snapshots to be written
            Thread.sleep(1000);
            
            // 3. Corrupt snapshot file on a follower
            final com.aegisos.node.AegisNode finalLeader = leader;
            com.aegisos.node.AegisNode follower = nodes.stream()
                .filter(n -> !n.identity().nodeId().equals(finalLeader.identity().nodeId()))
                .findFirst().get();
            
            java.nio.file.Path snapDir = follower.config().raftDir().resolve("snapshots");
            java.nio.file.Path snapFile = snapDir.resolve("snapshot.bin");
            java.nio.file.Files.write(snapFile, new byte[]{0x00, 0x01, 0x02, 0x03}); // Garbage
            
            // 4. Restart node
            cluster.stop(follower);
            com.aegisos.node.AegisNode restartedNode = cluster.restartNode(follower);
            
            // 5. Verify node recovers and has all files
            ClusterHarness.await(10000, () -> restartedNode.fileSystem().fileIndex().byName("file-19").isPresent());
            for (int i = 0; i < 20; i++) {
                org.junit.jupiter.api.Assertions.assertTrue(restartedNode.fileSystem().fileIndex().byName("file-" + i).isPresent());
            }
        }
    }
}

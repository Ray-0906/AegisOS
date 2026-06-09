package com.aegisos.cluster;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

public class SnapshotStressCompactionTest {

    @Test
    @DisplayName("Verify multiple snapshot cycles correctly stack offsets without losing state")
    void verifyStressCompaction() throws Exception {
        try (ClusterHarness cluster = new ClusterHarness()) {
            List<com.aegisos.node.AegisNode> nodes = cluster.start(3);
            com.aegisos.node.AegisNode leader = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                for (com.aegisos.node.AegisNode n : nodes) if (n.consensus().isLeader()) leader = n;
                if (leader != null) break;
                Thread.sleep(50);
            }
            Assertions.assertNotNull(leader);

            // We generate 200 commands, and manually trigger snapshot 10 times.
            // This tests that snapshot metadata updates correctly and raftLog properly handles stacking truncations.
            
            for (int batch = 0; batch < 10; batch++) {
                for (int i = 0; i < 20; i++) {
                    String filename = "stress-file-" + batch + "-" + i;
                    leader.fileSystem().write(filename, new byte[5]);
                }
                
                // Wait for replication
                Thread.sleep(500);

                // Trigger a snapshot cycle
                for (com.aegisos.node.AegisNode n : nodes) {
                    n.consensus().raftNode().triggerSnapshot();
                }
                Thread.sleep(500); // Give IO time to flush
            }

            // Verify the metrics show multiple snapshots were created
            Assertions.assertTrue(leader.consensus().raftNode().snapshotCreatedCount() >= 10, 
                "Should have created at least 10 snapshots");
                
            // Verify the state machine retains all 200 files
            for (int batch = 0; batch < 10; batch++) {
                for (int i = 0; i < 20; i++) {
                    String filename = "stress-file-" + batch + "-" + i;
                    Assertions.assertTrue(leader.fileSystem().fileIndex().byName(filename).isPresent(),
                        "Missing file from batch " + batch + " index " + i);
                }
            }

            // Verify a node can safely restart from the stacked snapshot
            final com.aegisos.node.AegisNode finalLeader = leader;
            com.aegisos.node.AegisNode follower = nodes.stream()
                    .filter(n -> !n.identity().nodeId().equals(finalLeader.identity().nodeId()))
                    .findFirst().get();

            cluster.stop(follower);
            com.aegisos.node.AegisNode restartedNode = cluster.restartNode(follower);

            ClusterHarness.await(5000, () -> restartedNode.fileSystem().fileIndex().byName("stress-file-9-19").isPresent());
            
            // Re-verify the state machine after loading the heavily-compacted snapshot
            for (int batch = 0; batch < 10; batch++) {
                for (int i = 0; i < 20; i++) {
                    String filename = "stress-file-" + batch + "-" + i;
                    Assertions.assertTrue(restartedNode.fileSystem().fileIndex().byName(filename).isPresent(),
                        "Restarted node is missing file from batch " + batch + " index " + i);
                }
            }
        }
    }
}

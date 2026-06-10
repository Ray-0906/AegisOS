package com.aegisos.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 6 acceptance test: InstallSnapshot RPC for lagging nodes.
 *
 * Phase 1: Leader has snapshot + truncated log
 * Phase 2: New node joins (needs entries before truncation point)
 * Phase 3: Leader sends InstallSnapshot RPC
 * Phase 4: New node catches up via snapshot + remaining log entries
 * Phase 5: New node has full state (voter set, files, artifacts, repair tasks)
 */
public class SnapshotTransferTest {

    @Test
    @DisplayName("Lagging node catches up via InstallSnapshot RPC")
    void snapshotTransfer() throws Exception {
        try (ClusterHarness cluster = new ClusterHarness()) {
            cluster.setReplicationFactor(2);
            java.util.List<com.aegisos.node.AegisNode> nodes = cluster.start(3);
            com.aegisos.node.AegisNode leader = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                for (com.aegisos.node.AegisNode n : nodes) if (n.consensus().isLeader()) leader = n;
                if (leader != null) break;
                Thread.sleep(50);
            }
            org.junit.jupiter.api.Assertions.assertNotNull(leader);

            // Phase 1: Leader has snapshot + truncated log
            for (int i = 0; i < 20; i++) {
                leader.fileSystem().write("file-" + i, new byte[100]);
            }
            Thread.sleep(1000); // Wait for replication

            leader.consensus().raftNode().triggerSnapshot();

            // Phase 2: Kill a follower, add more entries, wait for them to replicate to 2 nodes
            final com.aegisos.node.AegisNode finalLeader = leader;
            com.aegisos.node.AegisNode follower = nodes.stream()
                .filter(n -> !n.identity().nodeId().equals(finalLeader.identity().nodeId()))
                .findFirst().get();
            cluster.stop(follower);

            // Wait for gossip to mark it dead
            ClusterHarness.await(5000, () -> finalLeader.discovery().membership().alivePeerIds().size() == 1);

            for (int i = 20; i < 40; i++) {
                leader.fileSystem().write("file-" + i, new byte[100]);
            }
            Thread.sleep(1000); // Wait for replication to the remaining 2 nodes

            leader.consensus().raftNode().triggerSnapshot();

            // Phase 3 & 4 & 5: Restart the dead follower, it should catch up via InstallSnapshot
            com.aegisos.node.AegisNode restartedNode = cluster.restartNode(follower);
            
            // Wait for it to catch up
            Thread.sleep(3000);

            // It should have received the snapshot from the leader
            for (int i = 0; i < 40; i++) {
                org.junit.jupiter.api.Assertions.assertTrue(restartedNode.fileSystem().fileIndex().byName("file-" + i).isPresent());
            }
        }
    }
}

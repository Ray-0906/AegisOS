package com.aegisos.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a node successfully recovers its state from a snapshot and log 
 * after the log has been compacted.
 */
public class RecoveryAfterCompactionTest {

    @Test
    @DisplayName("Node recovers from snapshot and continues applying logs")
    void testRecoveryAfterCompaction() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            harness.setSnapshotEntryThreshold(30);
            harness.setJobSupervisorEnabled(false);
            harness.setReplicationFactor(1);
            var nodes = harness.start(1);
            var node = nodes.get(0);

            ClusterHarness.await(20000, () -> node.consensus().isLeader());

            // Write enough entries to trigger compaction
            for (int i = 0; i < 40; i++) {
                node.fileSystem().write("file-" + i, new byte[]{(byte) i});
            }

            // Wait for snapshot
            ClusterHarness.await(20000, () -> node.consensus().raftNode().raftLog().snapshotIndex() > 0);
            long snapIndex = node.consensus().raftNode().raftLog().snapshotIndex();
            assertTrue(snapIndex >= 30, "Snapshot must have occurred");

            // Write a few more entries that will be preserved in the log
            for (int i = 40; i < 50; i++) {
                node.fileSystem().write("file-" + i, new byte[]{(byte) i});
            }

            long lastAppliedBefore = node.consensus().raftNode().lastApplied();
            
            // Stop and restart the node
            harness.stop(node);
            var restartedNode = harness.restartNode(node);

            ClusterHarness.await(20000, () -> restartedNode.consensus().isLeader());

            long lastAppliedAfter = restartedNode.consensus().raftNode().lastApplied();
            assertTrue(lastAppliedAfter >= lastAppliedBefore, "Should have recovered all state");
            
            // Verify early entries are truncated
            assertNull(restartedNode.consensus().raftNode().raftLog().get(10), "Log should remain truncated after restart");
        }
    }
}

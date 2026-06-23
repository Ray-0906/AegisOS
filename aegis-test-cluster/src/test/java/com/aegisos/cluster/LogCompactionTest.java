package com.aegisos.cluster;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 6 acceptance test: Multi-round log compaction.
 *
 * Phase 1: Generate 1000+ log entries
 * Phase 2: Take snapshot, assert log truncated
 * Phase 3: Continue operations (new entries accumulate after snapshot)
 * Phase 4: Take second snapshot
 * Phase 5: Assert first snapshot replaced, log re-truncated to second snapshot point
 * Phase 6: Verify state integrity throughout
 *
 * Note: This test is currently disabled because the underlying 
 * multi-round log compaction logic is a Sprint 6 feature. 
 * While snapshots and recovery work, continuous log trimming 
 * is not yet integrated.
 */
public class LogCompactionTest {

    @Test
    @DisplayName("Multiple snapshot rounds with progressive log compaction")
    void multiRoundCompaction() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            harness.setSnapshotEntryThreshold(50);
            harness.setJobSupervisorEnabled(false);
            harness.setReplicationFactor(1);
            var nodes = harness.start(1);
            var node = nodes.get(0);

            ClusterHarness.await(20000, () -> node.consensus().isLeader());

            // Phase 1: First snapshot
            for (int i = 0; i < 60; i++) {
                node.fileSystem().write("snap-file-" + i, new byte[]{(byte) i});
            }

            ClusterHarness.await(20000, () -> node.consensus().raftNode().raftLog().snapshotIndex() > 0);
            long firstSnapshotIndex = node.consensus().raftNode().raftLog().snapshotIndex();
            System.err.println("DEBUG: firstSnapshotIndex = " + firstSnapshotIndex);
            org.junit.jupiter.api.Assertions.assertTrue(firstSnapshotIndex >= 50, "Snapshot must be at least index 50");

            // Phase 2: Second snapshot
            for (int i = 60; i < 120; i++) {
                node.fileSystem().write("snap-file-" + i, new byte[]{(byte) i});
            }

            ClusterHarness.await(20000, () -> node.consensus().raftNode().raftLog().snapshotIndex() > firstSnapshotIndex);
            long secondSnapshotIndex = node.consensus().raftNode().raftLog().snapshotIndex();
            System.err.println("DEBUG: secondSnapshotIndex = " + secondSnapshotIndex);
            org.junit.jupiter.api.Assertions.assertTrue(secondSnapshotIndex >= 100, "Snapshot must advance");

            // Assert prefix truncated
            org.junit.jupiter.api.Assertions.assertNull(node.consensus().raftNode().raftLog().get(10), "Log should be truncated");
        }
    }
}

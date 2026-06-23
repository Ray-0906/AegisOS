package com.aegisos.cluster;

import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a node successfully retains the snapshot boundary term,
 * even after its prefix is truncated.
 */
public class SnapshotBoundaryTermTest {

    @Test
    @DisplayName("Node retains boundary term after compaction")
    void testSnapshotBoundaryTerm() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            harness.setSnapshotEntryThreshold(30);
            harness.setReplicationFactor(1);
            var nodes = harness.start(1);
            var node = nodes.get(0);

            ClusterHarness.await(20000, () -> node.consensus().isLeader());

            // Write 40 entries
            for (int i = 0; i < 40; i++) {
                node.fileSystem().write("file-" + i, new byte[]{(byte) i});
            }

            // Wait for snapshot
            ClusterHarness.await(20000, () -> node.consensus().raftNode().raftLog().snapshotIndex() >= 30);
            long snapIndex = node.consensus().raftNode().raftLog().snapshotIndex();
            long snapTerm = node.consensus().raftNode().raftLog().snapshotTerm();

            assertTrue(snapIndex > 0, "Snapshot must have occurred");
            assertTrue(snapTerm > 0, "Snapshot term must be recorded");

            // Verify the log can still fetch the term for the snapshot index
            long fetchedTerm = node.consensus().raftNode().raftLog().termAt(snapIndex);
            assertEquals(snapTerm, fetchedTerm, "Log should return boundary term for the snapshot index");

            // Verify earlier indexes throw or return 0 (in our code it returns 0 for truncated indexes)
            long earlierTerm = node.consensus().raftNode().raftLog().termAt(10);
            assertEquals(0, earlierTerm, "Log should return 0 for truncated index terms");
        }
    }
}

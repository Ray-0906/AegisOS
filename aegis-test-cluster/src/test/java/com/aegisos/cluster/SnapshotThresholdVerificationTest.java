package com.aegisos.cluster;

import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate test: verifies that {@code ClusterHarness.setSnapshotEntryThreshold(25)} actually
 * causes the Raft log to compact after 30 writes.
 *
 * <p>This test MUST pass before writing any integration test that depends on
 * forced snapshot creation (SnapshotDuringExecutionTest, ExecutionRecoveryAfterSnapshotTest).
 */
public class SnapshotThresholdVerificationTest {

    private final ClusterHarness harness = new ClusterHarness();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void snapshotFiredAfterThresholdEntries() throws Exception {
        harness.setSnapshotEntryThreshold(25);
        harness.setJobSupervisorEnabled(false);
        harness.setReplicationFactor(1); // 1-node cluster requires RF=1
        List<AegisNode> nodes = harness.start(1);
        AegisNode node = nodes.get(0);

        // Wait for leader election
        boolean hasLeader = ClusterHarness.await(10_000, () -> node.consensus().isLeader());
        assertTrue(hasLeader, "Node must become leader");

        // Record snapshot index before writes (should be 0)
        long snapshotIndexBefore = node.consensus().raftNode().raftLog().snapshotIndex();

        // Write 30 entries (each write = 1+ Raft entries) -- must exceed threshold of 25
        for (int i = 0; i < 30; i++) {
            node.fileSystem().write("snap-verify-" + i, new byte[]{(byte) i});
        }

        // Allow time for the snapshot to fire.
        // We know a snapshot happened when snapshotIndex advances.
        boolean snapshotTaken = ClusterHarness.await(20_000,
                () -> node.consensus().raftNode().raftLog().snapshotIndex() > snapshotIndexBefore);

        assertTrue(snapshotTaken,
                "Expected snapshot to be taken after 30 writes with snapshotEntryThreshold=25. " +
                "Snapshot index before: " + snapshotIndexBefore +
                ", snapshot index after writes: " + node.consensus().raftNode().raftLog().snapshotIndex());
    }
}

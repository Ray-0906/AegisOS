package com.aegisos.cluster;

import com.aegisos.core.identity.NodeId;
import com.aegisos.network.NetworkLayer;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that a follower can successfully install a snapshot sent by a leader,
 * even after the leader has compacted its own log.
 */
public class InstallSnapshotAfterCompactionTest {

    @AfterEach
    void tearDown() {
        NetworkLayer.clearMessageFilter();
    }

    @Test
    @DisplayName("Follower successfully installs snapshot from compacted leader")
    void testInstallSnapshotAfterCompaction() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            harness.setSnapshotEntryThreshold(30);
            harness.setReplicationFactor(1);
            
            List<AegisNode> nodes = harness.start(3);
            
            AegisNode leader = null;
            for (int i = 0; i < 50; i++) {
                for (AegisNode n : nodes) {
                    if (n.consensus().isLeader()) {
                        leader = n;
                        break;
                    }
                }
                if (leader != null) break;
                Thread.sleep(100);
            }
            assertTrue(leader != null, "Cluster must have a leader");

            // Find a follower
            AegisNode follower = null;
            for (AegisNode n : nodes) {
                if (n != leader) {
                    follower = n;
                    break;
                }
            }
            assertTrue(follower != null, "Cluster must have a follower");

            // Partition follower so it falls behind
            NodeId followerId = follower.identity().nodeId();
            NetworkLayer.setMessageFilter((from, to) -> {
                if (from.equals(followerId) || to.equals(followerId)) {
                    return false;
                }
                return true;
            });

            // Write entries to leader to trigger compaction
            for (int i = 0; i < 50; i++) {
                com.aegisos.proto.StateCommand cmd = com.aegisos.proto.StateCommand.newBuilder()
                        .setType(com.aegisos.proto.CommandType.ADD_VOTER)
                        .setPayload(com.google.protobuf.ByteString.copyFromUtf8("dummy-" + i))
                        .build();
                leader.consensus().raftNode().submit(cmd.toByteArray());
            }

            // Wait for leader to snapshot and compact
            final AegisNode finalLeader = leader;
            ClusterHarness.await(20000, () -> finalLeader.consensus().raftNode().raftLog().snapshotIndex() >= 30);

            // Heal partition
            NetworkLayer.clearMessageFilter();

            // Wait for follower to catch up via InstallSnapshot
            final AegisNode finalFollower = follower;
            ClusterHarness.await(20000, () -> finalFollower.consensus().raftNode().raftLog().snapshotIndex() >= 30);

            assertTrue(finalFollower.consensus().raftNode().lastApplied() >= finalLeader.consensus().raftNode().raftLog().snapshotIndex(),
                    "Follower should have applied state from snapshot");
        }
    }
}

package com.aegisos.cluster;

import com.aegisos.consensus.RaftRole;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.StateCommand;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class SelfRemovalLeaderTest {

    @Test
    void testLeaderSelfRemoval() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            var nodes = harness.start(3);
            
            // Wait for leader
            AegisNode leader = null;
            for (int i = 0; i < 100; i++) {
                for (AegisNode n : nodes) {
                    if (n.consensus().isLeader()) {
                        leader = n;
                        break;
                    }
                }
                if (leader != null) break;
                Thread.sleep(50);
            }
            assertNotNull(leader);

            final AegisNode finalLeader = leader;

            // Leader A proposes REMOVE_VOTER(A)
            StateCommand removeCmd = StateCommand.newBuilder()
                    .setType(CommandType.REMOVE_VOTER)
                    .setPayload(com.google.protobuf.ByteString.copyFrom(finalLeader.identity().nodeId().toBytes()))
                    .build();

            finalLeader.consensus().propose(removeCmd).get(10, TimeUnit.SECONDS);

            // Leader A must step down to FOLLOWER
            boolean steppedDown = ClusterHarness.await(5000, () -> finalLeader.consensus().raftNode().role() == RaftRole.FOLLOWER);
            assertTrue(steppedDown, "Removed leader failed to step down");

            // A should no longer be a voter
            assertFalse(finalLeader.consensus().clusterConfiguration().isVoter(finalLeader.identity().nodeId()));

            // Either B or C should be elected as the new leader (voters are now B and C, quorum is 2/2)
            boolean newLeaderElected = ClusterHarness.await(10000, () -> 
                harness.nodes().stream().anyMatch(n -> n != finalLeader && n.consensus().isLeader())
            );
            assertTrue(newLeaderElected, "No new leader elected from the remaining voters");
        }
    }
}

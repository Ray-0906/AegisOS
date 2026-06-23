package com.aegisos.cluster;

import com.aegisos.consensus.RaftRole;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class VoterPromotionTest {

    @Test
    void testVoterPromotion() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            // harness.start(2) starts Node 0 (bootstrap) and Node 1 (promoted to voter automatically by harness)
            var nodes = harness.start(2);
            AegisNode node0 = nodes.get(0);
            AegisNode node1 = nodes.get(1);

            // Verify both are voters
            assertTrue(node0.consensus().clusterConfiguration().isVoter(node0.identity().nodeId()));
            assertTrue(node0.consensus().clusterConfiguration().isVoter(node1.identity().nodeId()));

            // Now stop node0 (leader)
            harness.stop(node0);

            // Node 1 should now time out and start a PreVote election
            boolean startedPreVote = ClusterHarness.await(5000, () -> node1.consensus().raftNode().getPreVoteStarts() > 0);
            assertTrue(startedPreVote, "Promoted node failed to start PreVote election after leader died");
        }
    }
}

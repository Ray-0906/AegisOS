package com.aegisos.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the solo-node election bug where a 1-node cluster
 * would never elect a leader because it had 0 voting peers to send RequestVote to.
 */
public class SingleNodeElectionTest {

    private ClusterHarness cluster;

    @AfterEach
    void tearDown() throws Exception {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void testSingleNodeBecomesLeader() throws Exception {
        try (ClusterHarness cluster = new ClusterHarness()) {
            var nodes = cluster.start(1);

            // A single node should elect itself leader immediately.
            boolean elected = ClusterHarness.await(5000, () -> nodes.get(0).consensus().isLeader());
            assertTrue(elected, "The single node should be the leader");
            assertNotNull(nodes.get(0).consensus().leaderId(), "Leader ID should not be null");
        }
    }
}

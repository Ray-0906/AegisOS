package com.aegisos.cluster;

import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 gate: a 5-node cluster converges membership, and a node going offline is
 * detected (marked DEAD / evicted) by the others within ~10 seconds.
 */
class Phase2Test {

    @Test
    void clusterConvergesAndDetectsFailure() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(5);

            boolean converged = ClusterHarness.await(15_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 5));
            assertTrue(converged, "all 5 nodes should see 5 alive members within 15s");

            // Kill one node; the rest must stop counting it as alive within ~10s.
            AegisNode victim = nodes.get(4);
            harness.stop(victim);
            List<AegisNode> survivors = nodes.subList(0, 4);

            boolean detected = ClusterHarness.await(12_000, () ->
                    survivors.stream().allMatch(n -> n.discovery().membership().aliveCount() == 4));
            assertTrue(detected, "survivors should detect the failed node within ~10s");
        }
    }
}

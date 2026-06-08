package com.aegisos.cluster;

import com.aegisos.core.identity.NodeId;
import com.aegisos.network.NetworkLayer;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

public class PartitionSafetyTest {

    @AfterEach
    void tearDown() {
        NetworkLayer.clearMessageFilter();
    }

    @Test
    void testPartitionSafety() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(5);

            // Wait for converge and leader election
            boolean converged = ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 5)
            );
            assertTrue(converged);

            boolean hasLeader = ClusterHarness.await(15_000, () ->
                    nodes.stream().filter(n -> n.consensus().isLeader()).count() == 1
            );
            assertTrue(hasLeader);

            AegisNode prePartitionLeader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst().orElseThrow();
            NodeId leaderId = prePartitionLeader.identity().nodeId();

            // Put the leader into the majority side, along with two other nodes.
            // The remaining two nodes form the minority side.
            java.util.List<NodeId> nonLeaders = nodes.stream()
                    .map(n -> n.identity().nodeId())
                    .filter(id -> !id.equals(leaderId))
                    .toList();

            Set<NodeId> minority = java.util.Set.of(nonLeaders.get(0), nonLeaders.get(1));
            Set<NodeId> majority = java.util.Set.of(leaderId, nonLeaders.get(2), nonLeaders.get(3));

            System.out.println("Applying network partition: {A, B} | {C, D, E}");
            NetworkLayer.setMessageFilter((from, to) -> {
                boolean fromMinority = minority.contains(from);
                boolean toMinority = minority.contains(to);
                boolean fromMajority = majority.contains(from);
                boolean toMajority = majority.contains(to);

                if (fromMinority && toMajority) return false;
                if (fromMajority && toMinority) return false;
                return true;
            });

            // Wait some time for re-elections or heartbeats to time out (3s)
            Thread.sleep(3000);

            // Verify minority side has NO leader
            long minorityLeaders = nodes.stream()
                    .filter(n -> minority.contains(n.identity().nodeId()))
                    .filter(n -> n.consensus().isLeader())
                    .count();
            assertEquals(0, minorityLeaders, "Minority side should not have any leader");

            // Verify majority side elects/keeps a leader
            boolean majorityHasLeader = ClusterHarness.await(10000, () ->
                    nodes.stream()
                            .filter(n -> majority.contains(n.identity().nodeId()))
                            .filter(n -> n.consensus().isLeader())
                            .count() == 1
            );
            assertTrue(majorityHasLeader, "Majority side failed to elect/keep a leader");

            // Heal partition
            System.out.println("Healing partition...");
            NetworkLayer.clearMessageFilter();

            // Wait for recovery (5s)
            Thread.sleep(5000);

            // Assert single leader remains across the whole cluster
            boolean convergedToSingleLeader = ClusterHarness.await(15_000, () ->
                    nodes.stream().filter(n -> n.consensus().isLeader()).count() == 1
            );
            assertTrue(convergedToSingleLeader, "Cluster did not converge to a single leader after healing partition");
        }
    }
}

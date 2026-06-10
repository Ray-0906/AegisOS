package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(120)
public class LeaderFailoverDuringRecoveryTest {

    @Test
    public void testLeaderFailoverDuringRecovery() throws Exception {
        System.setProperty("aegis.lease.duration.ms", "15000");
        System.setProperty("aegis.test.delay_after_lost", "true");
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(5);

            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 5)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode leader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst().get();

            JobHandle handle = leader.api().getProcessManager().submit(new SleepJob(30_000), 1, 128);
            String jobId = handle.jobId();

            assertTrue(ClusterHarness.await(15_000, () -> 
                leader.api().getProcessManager().status(jobId) == JobState.RUNNING
            ), "Job should start RUNNING");

            NodeId executorId = assignedNode(leader, jobId).orElseThrow();
            AegisNode executor = nodes.stream()
                    .filter(n -> n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();
                    
            // Stop the executor
            harness.stop(executor);

            if (executor != leader) {
                // Wait for leader to emit LOST
                assertTrue(ClusterHarness.await(25_000, () -> 
                    leader.api().getProcessManager().status(jobId) == JobState.LOST
                ), "Job should transition to LOST");

                // Stop the leader immediately after it emits LOST, but before it requeues
                // (delay_after_lost hook pauses it for 5s)
                harness.stop(leader);
            }

            AegisNode aliveNode = harness.nodes().stream()
                    .filter(n -> !n.identity().nodeId().equals(executorId) && (leader == executor || !n.identity().nodeId().equals(leader.identity().nodeId())))
                    .findFirst()
                    .orElseThrow();

            assertTrue(ClusterHarness.await(90_000, () -> 
                aliveNode.api().getProcessManager().status(jobId) == JobState.COMPLETED
            ), "Job should eventually COMPLETE");

            long executionId = aliveNode.runtimeAgent().registry().get(jobId).get().getExecutionId();
            assertTrue(executionId >= 2, "Execution ID should be >= 2");
            
            assertEquals(0, aliveNode.runtimeAgent().registry().invalidTransitionCount(), "No invalid transitions should occur");
            
            long drops = aliveNode.runtimeAgent().fencingDrops.get();
            assertTrue(drops <= 1, "fencingDrops should be <= 1, was " + drops);
        } finally {
            System.clearProperty("aegis.lease.duration.ms");
            System.clearProperty("aegis.test.delay_after_lost");
        }
    }

    private static Optional<NodeId> assignedNode(AegisNode node, String jobId) {
        return node.runtimeAgent().registry().get(jobId)
                .map(JobRecord::getAssignedNodeId)
                .filter(b -> !b.isEmpty())
                .map(b -> NodeId.of(b.toByteArray()));
    }
}

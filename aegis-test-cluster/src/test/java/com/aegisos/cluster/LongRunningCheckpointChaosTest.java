package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.CheckpointableSum;
import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LongRunningCheckpointChaosTest {

    @Test
    void testLongRunningJobSurvivesChaos() throws Exception {
        System.setProperty("aegis.lease.duration.ms", "10000"); // faster leases for test
        try (ClusterHarness harness = new ClusterHarness()) {
            harness.setAutoRemoveVoters(true); // Allow removing dead nodes from quorum
            harness.setReplicationFactor(2); // keep it robust but not too slow

            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() >= 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode submitter = nodes.get(0);

            // Sum to 100, sleeping 100ms per step. Total 10 seconds of compute time.
            JobHandle handle = submitter.api().getProcessManager().submit(new CheckpointableSum(100, 100), 1, 128);
            String jobId = handle.jobId();

            long deadline = System.currentTimeMillis() + 45_000;
            boolean finished = false;

            for (int i = 0; i < 3; i++) {
                // Wait for job to progress
                Thread.sleep(3000);
                
                // Check if it already finished unexpectedly fast
                if (submitter.runtimeAgent().registry().isTerminal(jobId)) {
                    break;
                }

                // Inject Chaos: Find the executor and kill it, OR find the leader and kill it
                AegisNode target = null;
                if (i % 2 == 0) {
                    // Kill executor
                    NodeId executorId = submitter.runtimeAgent().registry().get(jobId)
                            .map(JobRecord::getAssignedNodeId)
                            .filter(b -> !b.isEmpty())
                            .map(b -> NodeId.of(b.toByteArray())).orElse(null);
                    
                    if (executorId != null) {
                        target = harness.nodes().stream()
                                .filter(n -> n.identity().nodeId().equals(executorId))
                                .findFirst()
                                .orElse(null);
                    }
                } else {
                    // Kill leader
                    target = harness.nodes().stream()
                            .filter(n -> n.consensus().isLeader())
                            .findFirst()
                            .orElse(null);
                }

                if (target != null) {
                    System.out.println("Injecting chaos: killing node " + target.identity().nodeId().shortId());
                    harness.stop(target);
                }

                // Add a replacement node
                System.out.println("Adding replacement node to cluster");
                AegisNode replacement = harness.addNode();
                
                // Use the replacement as the new point of contact if the submitter was killed
                if (submitter == target) {
                    submitter = replacement;
                }
            }

            // Wait for completion
            AegisNode aliveNode = harness.nodes().stream()
                    .filter(n -> n.consensus().isLeader() || !n.consensus().isLeader()) // any alive
                    .findFirst()
                    .orElseThrow();

            Object result = aliveNode.api().getProcessManager().awaitResult(handle, 45_000);
            
            assertTrue(result instanceof Long);
            assertEquals(5050L, result, "Sum of 1..100 should be 5050");
        } finally {
            System.clearProperty("aegis.lease.duration.ms");
        }
    }
}

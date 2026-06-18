package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.CheckpointableSum;
import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobRecord;
import com.aegisos.testing.ClusterAwaiter;
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

            ClusterAwaiter awaiter = new ClusterAwaiter(harness);

            int targetSequence = 5;
            awaiter.awaitCheckpointCreated(jobId, targetSequence, java.time.Duration.ofSeconds(45));

            for (int i = 0; i < 3; i++) {
                com.aegisos.proto.JobState state = harness.getJobState(jobId);
                if (state == com.aegisos.proto.JobState.COMPLETED || state == com.aegisos.proto.JobState.FAILED || state == com.aegisos.proto.JobState.CANCELLED) {
                    break;
                }

                AegisNode target = null;
                if (i % 2 == 0) {
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
                    target = harness.nodes().stream()
                            .filter(n -> n.consensus().isLeader())
                            .findFirst()
                            .orElse(null);
                }

                if (target != null) {
                    long t0 = System.currentTimeMillis();
                    System.out.println("Injecting chaos: killing node " + target.identity().nodeId().shortId());
                    harness.stop(target);

                    System.out.println("Adding replacement node to cluster");
                    AegisNode replacement = harness.addNode();
                    if (submitter == target) submitter = replacement;

                    awaiter.awaitLeaderElection(java.time.Duration.ofSeconds(45));
                    long t1 = System.currentTimeMillis();

                    awaiter.awaitJobState(jobId, com.aegisos.proto.JobState.RUNNING, java.time.Duration.ofSeconds(45));
                    long t2 = System.currentTimeMillis();

                    targetSequence += 5; // wait for 5 more checkpoints to be generated
                    awaiter.awaitCheckpointCreated(jobId, targetSequence, java.time.Duration.ofSeconds(45));
                    long t3 = System.currentTimeMillis();

                    System.out.println("--- TIMING RUN " + i + " ---");
                    System.out.println("leader_election_ms: " + (t1 - t0));
                    System.out.println("reassignment_ms: " + (t2 - t1));
                    System.out.println("checkpoint_generation_ms: " + (t3 - t2));
                    System.out.println("total_ms: " + (t3 - t0));
                    System.out.println("----------------------");
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

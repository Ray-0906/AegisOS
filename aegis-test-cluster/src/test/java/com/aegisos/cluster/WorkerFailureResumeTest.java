package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.CheckpointableSum;
import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerFailureResumeTest {

    @Test
    void testWorkerFailureResumesFromCheckpoint() throws Exception {
        System.setProperty("aegis.lease.duration.ms", "15000");
        try (ClusterHarness harness = new ClusterHarness()) {
            harness.setReplicationFactor(2);
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode submitter = nodes.get(0);

            // Sum to 50, sleep 100ms per step. Takes 5 seconds.
            JobHandle handle = submitter.api().getProcessManager().submit(new CheckpointableSum(50, 100), 1, 128);
            String jobId = handle.jobId();

            // Wait for it to make at least 5 checkpoints
            assertTrue(ClusterHarness.await(10_000, () -> {
                var chk = submitter.runtimeAgent().registry().getCheckpoint(jobId);
                return chk.isPresent() && chk.get().metadata().getSequence() >= 5;
            }), "Job should create some checkpoints");

            // Find the node executing the job
            NodeId executorId = submitter.runtimeAgent().registry().get(jobId)
                    .map(JobRecord::getAssignedNodeId)
                    .filter(b -> !b.isEmpty())
                    .map(b -> NodeId.of(b.toByteArray())).orElseThrow();

            AegisNode executor = nodes.stream()
                    .filter(n -> n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();
                    
            // Kill the executor
            harness.stop(executor);

            AegisNode aliveNode = harness.nodes().stream()
                    .filter(n -> !n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();

            // Wait for lease to expire and job to finish successfully
            long startRecovery = System.currentTimeMillis();
            Object result = aliveNode.api().getProcessManager().awaitResult(handle, 45_000);
            
            // The result should be 1275 (sum of 1..50)
            assertTrue(result instanceof Long);
            assertEquals(1275L, result);
            
            long recoveryTime = System.currentTimeMillis() - startRecovery;
            // It should be fast since it resumed
            System.out.println("Recovery and completion took: " + recoveryTime + "ms");
        } finally {
            System.clearProperty("aegis.lease.duration.ms");
        }
    }
}

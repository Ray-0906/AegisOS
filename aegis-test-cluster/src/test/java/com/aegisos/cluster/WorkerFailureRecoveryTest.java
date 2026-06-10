package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class WorkerFailureRecoveryTest {

    @Test
    void testWorkerFailureTriggersRequeue() throws Exception {
        // Use a short lease so the test runs faster, though prompt mentioned 15s,
        // we'll explicitly set to 5s so we don't wait forever, and wait proportionally.
        // Actually, if prompt explicitly says "lease expires (15s)", we will use 15s
        System.setProperty("aegis.lease.duration.ms", "15000");
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode submitter = nodes.get(0);

            // Submit a job that runs long enough for us to kill the worker
            JobHandle handle = submitter.api().getProcessManager().submit(new SleepJob(60_000), 1, 128);
            String jobId = handle.jobId();

            // Wait for it to become RUNNING
            assertTrue(ClusterHarness.await(10_000, () -> {
                JobState state = submitter.api().getProcessManager().status(jobId);
                return state == JobState.RUNNING;
            }), "Job should transition to RUNNING");

            // Find the node executing the job
            NodeId executorId = assignedNode(submitter, jobId).orElseThrow();
            AegisNode executor = nodes.stream()
                    .filter(n -> n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();
                    
            // Stop the executor to simulate failure
            harness.stop(executor);

            // Use an alive node to poll the status in case submitter == executor
            AegisNode aliveNode = harness.nodes().stream()
                    .filter(n -> !n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();

            // Wait for lease to expire (15s) and job to be marked LOST, then QUEUED/RUNNING on a new node
            assertTrue(ClusterHarness.await(45_000, () -> {
                return aliveNode.runtimeAgent().registry().get(jobId)
                    .map(r -> r.getExecutionId() >= 2 && (r.getState() == JobState.RUNNING || r.getState() == JobState.QUEUED))
                    .orElse(false);
            }), "Job should be recovered and RUNNING/QUEUED with executionId >= 2");
            
            // Clean up the running job so the test doesn't hang background processes
            aliveNode.runtimeAgent().cancelJob(jobId);
        } finally {
            System.clearProperty("aegis.lease.duration.ms");
        }
    }
    
    private static Optional<NodeId> assignedNode(AegisNode node, String jobId) {
        return node.runtimeAgent().registry().get(jobId)
                .map(JobRecord::getAssignedNodeId)
                .filter(b -> !b.isEmpty())
                .map(b -> NodeId.of(b.toByteArray()));
    }
}

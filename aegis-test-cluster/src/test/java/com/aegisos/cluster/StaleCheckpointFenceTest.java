package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.CheckpointableSum;
import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class StaleCheckpointFenceTest {

    @Test
    void testStaleCheckpointsAreFenced() throws Exception {
        System.setProperty("aegis.lease.duration.ms", "10000");
        try (ClusterHarness harness = new ClusterHarness()) {
            harness.setReplicationFactor(2);
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode submitter = nodes.get(0);

            // Job runs for 10 seconds (100 steps * 100ms)
            JobHandle handle = submitter.api().getProcessManager().submit(new CheckpointableSum(100, 100), 1, 128);
            String jobId = handle.jobId();

            assertTrue(ClusterHarness.await(10_000, () -> {
                var chk = submitter.runtimeAgent().registry().getCheckpoint(jobId);
                return chk.isPresent() && chk.get().metadata().getSequence() >= 1;
            }), "Job should create some checkpoints");

            NodeId executorId = submitter.runtimeAgent().registry().get(jobId)
                    .map(JobRecord::getAssignedNodeId)
                    .filter(b -> !b.isEmpty())
                    .map(b -> NodeId.of(b.toByteArray())).orElseThrow();

            AegisNode executor = nodes.stream()
                    .filter(n -> n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();

            // Isolate executor
            com.aegisos.network.NetworkLayer.setMessageFilter((from, to) -> {
                if (from.equals(executorId) || to.equals(executorId)) return false;
                return true;
            });

            AegisNode aliveNode = harness.nodes().stream()
                    .filter(n -> !n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();

            // Wait for lease to expire and job to be recovered (executionId >= 2)
            assertTrue(ClusterHarness.await(45_000, () -> {
                var job = aliveNode.runtimeAgent().registry().get(jobId);
                return job.isPresent() && job.get().getExecutionId() >= 2 && job.get().getState() == JobState.RUNNING;
            }), "Job should be recovered and RUNNING with new executionId");

            // Allow the old isolated executor some time to blindly produce more checkpoints
            // that will be queued for Raft replication (but failing)
            Thread.sleep(3000);

            // Wait for the new execution to make checkpoints
            assertTrue(ClusterHarness.await(15_000, () -> {
                var chk = aliveNode.runtimeAgent().registry().getCheckpoint(jobId);
                return chk.isPresent() && chk.get().executionId() >= 2 && chk.get().metadata().getSequence() > 0;
            }), "New execution should create checkpoints");

            var latestSeq = aliveNode.runtimeAgent().registry().getCheckpoint(jobId).get().metadata().getSequence();

            // Reconnect the old executor
            com.aegisos.network.NetworkLayer.clearMessageFilter();

            // Wait for job to finish
            aliveNode.api().getProcessManager().awaitResult(handle, 90_000);

            // The last checkpoint must belong to the new execution (executionId >= 2)
            var finalChk = aliveNode.runtimeAgent().registry().getCheckpoint(jobId).get();
            assertTrue(finalChk.executionId() >= 2, "Final checkpoint must be from the new execution, not stale overwrites");
            assertTrue(finalChk.metadata().getSequence() >= latestSeq, "Sequence should not be rolled back by old node");
        } finally {
            com.aegisos.network.NetworkLayer.clearMessageFilter();
            System.clearProperty("aegis.lease.duration.ms");
        }
    }
}

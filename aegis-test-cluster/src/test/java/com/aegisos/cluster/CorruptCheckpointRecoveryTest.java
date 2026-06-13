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

public class CorruptCheckpointRecoveryTest {

    @Test
    void testCorruptCheckpointFailsJob() throws Exception {
        System.setProperty("aegis.lease.duration.ms", "10000");
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode submitter = nodes.get(0);

            JobHandle handle = submitter.api().getProcessManager().submit(new CheckpointableSum(50, 100), 1, 128);
            String jobId = handle.jobId();

            assertTrue(ClusterHarness.await(10_000, () -> {
                var chk = submitter.runtimeAgent().registry().getCheckpoint(jobId);
                return chk.isPresent() && chk.get().metadata().getSequence() >= 2;
            }), "Job should create some checkpoints");

            NodeId executorId = submitter.runtimeAgent().registry().get(jobId)
                    .map(JobRecord::getAssignedNodeId)
                    .filter(b -> !b.isEmpty())
                    .map(b -> NodeId.of(b.toByteArray())).orElseThrow();

            AegisNode executor = nodes.stream()
                    .filter(n -> n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();

            // Find checkpoint file path
            var chk = submitter.runtimeAgent().registry().getCheckpoint(jobId).get();
            String checkpointPath = chk.checkpointFileId();

            // Corrupt it
            submitter.fileSystem().write(checkpointPath, new byte[]{0, 1, 2, 3}); // Truncated/corrupt byte array

            // Stop the executor so the job gets requeued
            harness.stop(executor);

            AegisNode aliveNode = harness.nodes().stream()
                    .filter(n -> !n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();

            // Wait for lease to expire and job to transition to FAILED due to bad checkpoint
            assertTrue(ClusterHarness.await(45_000, () -> {
                JobState state = aliveNode.api().getProcessManager().status(jobId);
                System.out.println("[TEST-DEBUG] Current state = " + state);
                return state == JobState.FAILED;
            }), "Job should fail after attempting to load corrupt checkpoint");
        } finally {
            System.clearProperty("aegis.lease.duration.ms");
        }
    }
}

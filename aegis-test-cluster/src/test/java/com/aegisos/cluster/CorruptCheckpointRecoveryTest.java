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
            com.aegisos.testing.ClusterAwaiter awaiter = new com.aegisos.testing.ClusterAwaiter(harness);
            
            awaiter.awaitQuorum(java.time.Duration.ofSeconds(20));
            awaiter.awaitLeaderElection(java.time.Duration.ofSeconds(20));

            AegisNode submitter = nodes.get(0);

            JobHandle handle = submitter.api().getProcessManager().submit(new CheckpointableSum(50, 100), 1, 128);
            String jobId = handle.jobId();

            new com.aegisos.testing.EventAwaiter().withTimeout(java.time.Duration.ofSeconds(10)).await(() -> {
                var chk = submitter.runtimeAgent().registry().getCheckpoint(jobId);
                return chk.isPresent() && chk.get().metadata().getSequence() >= 2;
            });

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
            
            // Wait for node death detection and lease expiration
            awaiter.awaitNodeDeath(executorId, java.time.Duration.ofSeconds(20));
            awaiter.awaitWorkerLeaseExpiration(executorId, java.time.Duration.ofSeconds(20));

            // Wait for job to transition to FAILED due to bad checkpoint
            awaiter.awaitJobState(jobId, JobState.FAILED, java.time.Duration.ofSeconds(45));
        } finally {
            System.clearProperty("aegis.lease.duration.ms");
        }
    }
}

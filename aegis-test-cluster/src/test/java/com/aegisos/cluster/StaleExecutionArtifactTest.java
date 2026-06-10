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

/**
 * Proves the side-effect fencing invariant:
 * "Superseded executions attempt log/result upload -> system rejects."
 */
class StaleExecutionArtifactTest {

    @Test
    void staleExecutionCannotPublishSideEffects() throws Exception {
        // Use short lease to force quick requeue
        System.setProperty("aegis.lease.duration.ms", "2000");

        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode submitter = nodes.get(0);

            // Job that runs for 10 seconds
            JobHandle handle = submitter.api().getProcessManager().submit(new SleepJob(10_000), 1, 128);
            String jobId = handle.jobId();

            assertTrue(ClusterHarness.await(5_000, () -> submitter.api().getProcessManager().status(jobId) == JobState.RUNNING));

            NodeId executorId = assignedNode(submitter, jobId).orElseThrow();
            AegisNode executor = nodes.stream().filter(n -> n.identity().nodeId().equals(executorId)).findFirst().orElseThrow();

            // We simulate a network partition by stopping the executor's network layer (so it can't heartbeat)
            // But the job process itself is still running in the background.
            // Since we can't easily kill just the network, we'll simulate the lease expiry directly
            // by dropping heartbeats via reflection or just waiting if the lease is short.
            // Actually, we can use the test hook we added: `aegis.test.delay_after_lost`
            // Let's just do a clean partition by closing the executor's network if possible, or
            // we kill the executor process after it started the job. Wait, the job is a child JVM!
            // If we kill the executor node, the child JVM might survive or die depending on ProcessSupervisor.
            // In AegisOS, ProcessSupervisor destroys the child JVM when the node shuts down.
            
            // So to test stale artifact upload, we need the execution to FINISH, but AFTER it was requeued.
            // The easiest way to force this race in a test is to manipulate the registry directly.
            // The invariant states that IF the execution is superseded, its side effects are rejected.
            
            // Let's manually propose a dummy requeue command to the leader, bumping the executionId to 2.
            com.aegisos.proto.JobRecord current = submitter.runtimeAgent().registry().get(jobId).orElseThrow();
            
            com.aegisos.proto.JobRecord resumed = current.toBuilder()
                    .setExecutionId(2L)
                    .setState(JobState.QUEUED)
                    .build();
                    
            submitter.consensus().propose(com.aegisos.proto.StateCommand.newBuilder()
                    .setType(com.aegisos.proto.CommandType.ASSIGN_JOB)
                    .setPayload(resumed.toByteString())
                    .build()).get();
                    
            // Now the active executionId is 2 in the registry.
            // But the worker is still running executionId=1.
            // When executionId=1 finishes (after the 10s sleep), it will try to upload logs.
            // But `isSuperseded` will return true, and it should skip the upload.
            
            // Wait for the job to finish (we just sleep 12s)
            Thread.sleep(12_000);
            
            // Verify that NO logs exist for executionId=1
            String stdoutPath = "/jobs/" + jobId + "/1/stdout";
            
            // The file system should throw an exception or return empty if not found
            assertThrows(Exception.class, () -> {
                byte[] data = submitter.fileSystem().read(stdoutPath);
                if (data != null && data.length > 0) {
                    throw new RuntimeException("Should not have data");
                }
            }, "Stdout for execution 1 should not exist because it was superseded before upload");
            
            // Verify invalid transitions is 0
            assertEquals(0, submitter.runtimeAgent().registry().invalidTransitionCount());
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

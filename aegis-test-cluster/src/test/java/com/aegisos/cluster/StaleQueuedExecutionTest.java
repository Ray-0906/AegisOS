package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StaleQueuedExecutionTest {

    @Test
    void testStaleRunJobIgnored() throws Exception {
        System.setProperty("aegis.queued.stale.ms", "5000"); // 5 seconds
        
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode submitter = nodes.get(0);
            
            // To simulate a delayed RUN_JOB, we can intercept and drop RUN_JOB messages 
            // for a specific node, wait for the leader to requeue it, and then manually 
            // inject the old RUN_JOB.
            
            AegisNode target = nodes.get(1);
            AegisNode leader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst().orElseThrow();
            
            // We want to capture the RUN_JOB message and prevent it from reaching the target initially.
            java.util.concurrent.atomic.AtomicReference<byte[]> capturedRunJob = new java.util.concurrent.atomic.AtomicReference<>();
            com.aegisos.network.NetworkLayer.setMessageFilter((from, to, type, payload) -> {
                if (to.equals(target.identity().nodeId()) && type == com.aegisos.core.message.MessageType.RUN_JOB) {
                    return false; // Drop RUN_JOB to simulate delayed packet
                }
                return true; 
            });

            // Manually inject the job assigned to target to bypass random scheduler assignment
            String jobId = java.util.UUID.randomUUID().toString();
            com.aegisos.proto.JobSpec spec = com.aegisos.proto.JobSpec.newBuilder()
                    .setJobId(jobId)
                    .setClassName(SleepJob.class.getName())
                    .setArgs(com.google.protobuf.ByteString.copyFrom(new byte[0]))
                    .build();

            JobRecord record1 = JobRecord.newBuilder()
                    .setSpec(spec)
                    .setState(JobState.QUEUED)
                    .setExecutionId(1L)
                    .setAssignedNodeId(com.google.protobuf.ByteString.copyFrom(target.identity().nodeId().toBytes()))
                    .build();

            leader.consensus().propose(com.aegisos.proto.StateCommand.newBuilder()
                    .setType(com.aegisos.proto.CommandType.ASSIGN_JOB)
                    .setPayload(record1.toByteString())
                    .build()).get();

            // Wait for it to be QUEUED in the target's registry
            assertTrue(ClusterHarness.await(10_000, () -> {
                Optional<JobRecord> r = target.runtimeAgent().registry().get(jobId);
                return r.isPresent() && r.get().getExecutionId() == 1 && r.get().getState() == JobState.QUEUED;
            }), "Job should be assigned to target");

            // Now manually propose LOST for executionId 1 to force a requeue.
            // This simulates JobSupervisor deciding the node died or the lease expired.
            com.aegisos.proto.JobUpdate lostUpdate = com.aegisos.proto.JobUpdate.newBuilder()
                    .setJobId(jobId)
                    .setExecutionId(1L)
                    .setState(JobState.LOST)
                    .build();
            leader.consensus().propose(com.aegisos.proto.StateCommand.newBuilder()
                    .setType(com.aegisos.proto.CommandType.UPDATE_JOB)
                    .setPayload(lostUpdate.toByteString())
                    .build()).get();

            // Wait for the target to requeue it (executionId >= 2)
            assertTrue(ClusterHarness.await(25_000, () -> {
                Optional<JobRecord> rec = target.runtimeAgent().registry().get(jobId);
                return rec.isPresent() && rec.get().getExecutionId() > 1;
            }), "Job should be requeued");

            // At this point, executionId=2 is active. 
            // Let's manually dispatch executionId=1 to the target node
            // to simulate the delayed network packet finally arriving.
            long beforeStarted = target.runtimeAgent().jobsStarted.get();

            // Dispatch directly to the agent (simulates receiving a delayed RUN_JOB)
            target.runtimeAgent().dispatchLocal(record1);
            
            // Wait a moment
            Thread.sleep(3000);
            
            // The stale execution should abort immediately or at least not transition anything
            long afterStarted = target.runtimeAgent().jobsStarted.get();
            assertEquals(beforeStarted, afterStarted, "Agent should not start a stale execution");
        } finally {
            com.aegisos.network.NetworkLayer.clearMessageFilter();
            System.clearProperty("aegis.queued.stale.ms");
        }
    }
}

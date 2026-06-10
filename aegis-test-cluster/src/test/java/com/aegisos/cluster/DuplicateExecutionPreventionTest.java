package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.UninterruptibleSleepJob;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobState;
import com.aegisos.runtime.JobSupervisor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DuplicateExecutionPreventionTest {

    @BeforeEach
    void setup() {
        System.setProperty("aegis.test.delay_after_lost", "true");
    }

    @AfterEach
    void teardown() {
        System.clearProperty("aegis.test.delay_after_lost");
    }

    @Test
    void testDuplicateExecutionFenced() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            Thread.sleep(1_000); // let resources propagate

            AegisNode submitter = nodes.get(0);
            
            // Find leader
            AegisNode leader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No leader"));

            // Submit jobs until one lands on a follower
            JobHandle handle = null;
            for (int i = 0; i < 5; i++) {
                JobHandle h = submitter.api().getProcessManager().submit(new UninterruptibleSleepJob(25_000), 1, 100);
                assertTrue(ClusterHarness.await(10_000, () ->
                        submitter.api().getProcessManager().status(h.jobId()) == JobState.RUNNING));
                com.aegisos.core.identity.NodeId assignedId = com.aegisos.core.identity.NodeId.of(
                        submitter.runtimeAgent().registry().get(h.jobId()).get().getAssignedNodeId().toByteArray());
                if (!assignedId.equals(leader.identity().nodeId())) {
                    handle = h;
                    break;
                }
            }
            assertTrue(handle != null, "Could not get a job scheduled on a follower");

            // Get JobSupervisor and lastHeartbeat map
            Field jsField = AegisNode.class.getDeclaredField("jobSupervisor");
            jsField.setAccessible(true);
            JobSupervisor js = (JobSupervisor) jsField.get(leader);

            Field lhField = JobSupervisor.class.getDeclaredField("lastHeartbeat");
            lhField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Long> lastHeartbeat = (Map<String, Long>) lhField.get(js);

            // Force lease expiration: keep clearing lastHeartbeat for 16 seconds.
            // The supervisor scans every 3s and requires 15s of no heartbeat.
            System.out.println("Dropping heartbeats to simulate network partition...");
            long deadline = System.currentTimeMillis() + 16_000;
            while (System.currentTimeMillis() < deadline) {
                lastHeartbeat.remove(handle.jobId());
                Thread.sleep(100);
            }

            System.out.println("Waiting for job to complete after failover...");
            // The job should complete successfully (from Node B's execution)
            Boolean result = submitter.api().getProcessManager().awaitResult(handle, 60_000);
            assertTrue(result, "Job should return true");

            // Ensure no invalid state transitions occurred during fencing
            int invalidTransitions = submitter.runtimeAgent().registry().invalidTransitionCount();
            assertEquals(0, invalidTransitions, "Should be 0 invalid transitions (fencing successful)");

            // Verify executionId bumped
            long finalExecutionId = submitter.runtimeAgent().registry().get(handle.jobId()).get().getExecutionId();
            assertTrue(finalExecutionId > 1, "Execution ID should have been bumped due to LOST requeue");
        }
    }
}

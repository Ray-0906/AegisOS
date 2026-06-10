package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LeaderFailoverJobRecoveryTest {

    @Test
    void testLeaderFailoverJobRecovery() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            Thread.sleep(1_000); // Let resources propagate

            AegisNode submitter = nodes.get(0);

            // Submit jobs until we find one scheduled on a follower.
            JobHandle handle = null;
            AegisNode leader = null;
            AegisNode worker = null;

            for (int i = 0; i < 5; i++) {
                handle = submitter.api().getProcessManager().submit(new SleepJob(15_000), 1, 100);
                JobHandle h = handle;
                assertTrue(ClusterHarness.await(10_000, () ->
                        submitter.api().getProcessManager().status(h.jobId()) == JobState.RUNNING));

                AegisNode currentLeader = nodes.stream()
                        .filter(n -> n.consensus().isLeader())
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No leader"));
                
                NodeId assignedId = NodeId.of(submitter.runtimeAgent().registry().get(h.jobId()).get().getAssignedNodeId().toByteArray());
                AegisNode currentWorker = nodes.stream()
                        .filter(n -> n.identity().nodeId().equals(assignedId))
                        .findFirst()
                        .get();

                if (!currentWorker.identity().nodeId().equals(currentLeader.identity().nodeId())) {
                    leader = currentLeader;
                    worker = currentWorker;
                    break;
                }
            }

            assertTrue(worker != null && leader != null, "Could not schedule job on a follower");

            long initialExecutionId = worker.runtimeAgent().registry().get(handle.jobId()).get().getExecutionId();
            assertEquals(1L, initialExecutionId, "Initial executionId should be 1");

            System.out.println("Stopping leader " + leader.identity().nodeId().shortId() + "...");
            harness.stop(leader);

            // Wait for a new leader to emerge among the remaining 2 nodes
            assertTrue(ClusterHarness.await(15_000, () ->
                    harness.nodes().stream().anyMatch(n -> n.consensus().isLeader())));

            // Wait for the job to complete
            System.out.println("Waiting for job " + handle.jobId() + " to finish...");
            
            // Note: Use one of the SURVIVING nodes to wait for the result
            AegisNode survivor = harness.nodes().get(0);
            Boolean result = survivor.api().getProcessManager().awaitResult(handle, 30_000);
            assertTrue(result, "Job should return true");

            // Verify executionId remained 1
            long finalExecutionId = survivor.runtimeAgent().registry().get(handle.jobId()).get().getExecutionId();
            assertEquals(1L, finalExecutionId, "Execution ID should not change after leader failover");

            // Verify no invalid transitions
            assertEquals(0, survivor.runtimeAgent().registry().invalidTransitionCount(),
                    "Should be 0 invalid transitions");
        }
    }
}

package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.PrimeCounter;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JobLifecycleTest {

    @Test
    void testNormalJobLifecycle() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode submitter = nodes.get(0);

            // Submit a simple job that will complete quickly
            JobHandle handle = submitter.api().getProcessManager().submit(new PrimeCounter(100), 1, 128);
            String jobId = handle.jobId();

            // Wait for completion (either COMPLETED or FAILED, though we expect COMPLETED)
            assertTrue(ClusterHarness.await(10_000, () -> {
                JobState state = submitter.api().getProcessManager().status(jobId);
                return state == JobState.COMPLETED;
            }), "Job should transition to COMPLETED");

            // Verify invariant: No invalid state transitions occurred
            assertEquals(0, submitter.runtimeAgent().registry().invalidTransitionCount(), 
                    "Invalid transition count should be 0");
        }
    }
}

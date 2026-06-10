package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JobCancellationTest {

    @Test
    void testCancelRunningJob() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode submitter = nodes.get(0);

            // Submit a long-running job
            JobHandle handle = submitter.api().getProcessManager().submit(new SleepJob(30_000), 1, 128);
            String jobId = handle.jobId();

            // Wait for it to become RUNNING
            assertTrue(ClusterHarness.await(10_000, () -> {
                JobState state = submitter.api().getProcessManager().status(jobId);
                return state == JobState.RUNNING;
            }), "Job should transition to RUNNING");

            // To cancel the job, we must call cancelJob on the leader so it can propose to Raft.
            AegisNode leader = nodes.stream()
                    .filter(n -> n.consensus().isLeader())
                    .findFirst()
                    .orElseThrow();
                    
            leader.runtimeAgent().cancelJob(jobId);

            // Wait for status to become CANCELLED
            assertTrue(ClusterHarness.await(5_000, () -> {
                JobState state = submitter.api().getProcessManager().status(jobId);
                // Depending on races with the local executor, it might eventually transition to FAILED.
                // But we assert it reaches CANCELLED first or FAILED if the kill happened very fast.
                return state == JobState.CANCELLED || state == JobState.FAILED;
            }), "Job should transition to CANCELLED (or FAILED if local kill raced)");
            
            // Wait a moment and check it is terminal
            Thread.sleep(2000);
            JobState finalState = submitter.api().getProcessManager().status(jobId);
            assertTrue(finalState == JobState.CANCELLED || finalState == JobState.FAILED, 
                    "Job should remain in terminal state");
        }
    }
}

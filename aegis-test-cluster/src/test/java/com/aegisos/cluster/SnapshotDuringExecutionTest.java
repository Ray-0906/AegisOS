package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
public class SnapshotDuringExecutionTest {

    @Test
    public void testSnapshotDuringExecution() throws Exception {
        System.setProperty("aegis.snapshot.entryThreshold", "5");
        try (ClusterHarness harness = new ClusterHarness()) {
            harness.setSnapshotEntryThreshold(5);
            List<AegisNode> nodes = harness.start(3);

            boolean elected = ClusterHarness.await(15_000,
                    () -> nodes.stream().allMatch(n -> n.consensus().leaderId() != null));
            assertTrue(elected, "Leader should be elected");

            AegisNode submitter = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst().get();

            JobHandle handle = submitter.api().getProcessManager().submit(new SleepJob(10_000), 1, 128);
            String jobId = handle.jobId();

            assertTrue(ClusterHarness.await(10_000, () -> 
                submitter.api().getProcessManager().status(jobId) == JobState.RUNNING
            ), "Job should start RUNNING");

            // Write 7 files to force snapshot
            for (int i = 0; i < 7; i++) {
                submitter.fileSystem().write("snap-trigger-" + i, new byte[]{(byte)i});
            }

            // Assert snapshot taken (snapshotIndex > 0)
            assertTrue(ClusterHarness.await(15_000, () -> 
                nodes.stream().anyMatch(n -> n.consensus().raftNode().raftLog().snapshotIndex() > 0)
            ), "Snapshot should be taken");

            assertEquals(JobState.RUNNING, submitter.api().getProcessManager().status(jobId), "Job should STILL be RUNNING");

            // Wait for completion
            assertTrue(ClusterHarness.await(15_000, () -> 
                submitter.api().getProcessManager().status(jobId) == JobState.COMPLETED
            ), "Job should complete");

            // Invalid transition check
            assertEquals(0, submitter.runtimeAgent().registry().invalidTransitionCount(), "No invalid transitions should occur");
            
            // Check logs exist
            assertTrue(ClusterHarness.await(5_000, () ->
                submitter.fileSystem().fileIndex().byName("/jobs/" + jobId + "/1/stdout").isPresent()
            ), "Stdout should exist in AegisFS");
        } finally {
            System.clearProperty("aegis.snapshot.entryThreshold");
        }
    }
}

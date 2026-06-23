package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobState;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TerminalStateFirstWriterWinsTest {

    public static class SyncRaceJob implements AegisJob<String> {
        private final String triggerPath;

        public SyncRaceJob() {
            this.triggerPath = "";
        }

        public SyncRaceJob(String triggerPath) {
            this.triggerPath = triggerPath;
        }

        @Override
        public String execute(JobContext ctx) throws Exception {
            File trigger = new File(triggerPath);
            // Wait up to 10 seconds for the file to be created
            for (int i = 0; i < 100; i++) {
                if (trigger.exists()) {
                    return "COMPLETED_SYNC";
                }
                Thread.sleep(100);
            }
            throw new RuntimeException("Trigger file never created");
        }
    }

    @Test
    void testCompleteVsCancelRace() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode submitter = nodes.get(0);
            Path triggerFile = Files.createTempFile("aegis_race_", ".trigger");
            // Delete it initially
            Files.delete(triggerFile);

            int iterations = 10;
            for (int i = 0; i < iterations; i++) {
                JobHandle handle = submitter.api().getProcessManager().submit(new SyncRaceJob(triggerFile.toAbsolutePath().toString()), 1, 128);
                String jobId = handle.jobId();

                // Wait for it to become RUNNING
                assertTrue(ClusterHarness.await(10_000, () -> {
                    JobState state = submitter.api().getProcessManager().status(jobId);
                    return state == JobState.RUNNING;
                }), "Job should transition to RUNNING");

                AegisNode leader = nodes.stream()
                        .filter(n -> n.consensus().isLeader())
                        .findFirst()
                        .orElseThrow();

                // Create the file to unblock the worker, and IMMEDIATELY cancel the job.
                // This forces ProcessRuntimeAgent to handle COMPLETION while leader is proposing CANCELLED.
                Files.write(triggerFile, new byte[]{1});
                leader.runtimeAgent().cancelJob(jobId);

                // Wait for status to become a terminal state
                assertTrue(ClusterHarness.await(10_000, () -> {
                    JobState state = submitter.api().getProcessManager().status(jobId);
                    return state == JobState.COMPLETED || state == JobState.CANCELLED;
                }), "Job should transition to a terminal state");
                
                // Sleep a bit more to ensure it doesn't oscillate
                Thread.sleep(1000);
                
                JobState finalState = submitter.api().getProcessManager().status(jobId);
                assertTrue(finalState == JobState.COMPLETED || finalState == JobState.CANCELLED,
                        "State must be strictly COMPLETED or CANCELLED, but got " + finalState);

                // Prepare for next iteration
                Files.delete(triggerFile);
            }
        }
    }
}

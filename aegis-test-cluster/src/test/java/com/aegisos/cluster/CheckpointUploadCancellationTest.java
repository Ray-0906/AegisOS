package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobState;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class CheckpointUploadCancellationTest {

    public static class LargeCheckpointJob implements AegisJob<String> {
        private final String triggerPath;
        private final String ackPath;

        public LargeCheckpointJob() {
            this.triggerPath = "";
            this.ackPath = "";
        }

        public LargeCheckpointJob(String triggerPath, String ackPath) {
            this.triggerPath = triggerPath;
            this.ackPath = ackPath;
        }

        @Override
        public String execute(JobContext ctx) throws Exception {
            File trigger = new File(triggerPath);
            File ack = new File(ackPath);

            // Wait for test to trigger
            for (int i = 0; i < 100; i++) {
                if (trigger.exists()) {
                    break;
                }
                Thread.sleep(100);
            }

            // Signal test we are about to checkpoint
            Files.write(ack.toPath(), new byte[]{1});

            // Brief delay to allow test to issue CANCEL
            Thread.sleep(100);

            // This will take a while over the socket and get interrupted by kill()
            ctx.checkpoint();

            return "COMPLETED";
        }

        @Override
        public byte[] captureState() {
            // 100 MB checkpoint
            return new byte[100 * 1024 * 1024];
        }

        @Override
        public void restoreState(byte[] state) {
        }
    }

    @Test
    void testCancelDuringCheckpointUpload() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode submitter = nodes.get(0);
            Path triggerFile = Files.createTempFile("aegis_chk_trigger_", ".tmp");
            Path ackFile = Files.createTempFile("aegis_chk_ack_", ".tmp");
            Files.delete(triggerFile);
            Files.delete(ackFile);

            JobHandle handle = submitter.api().getProcessManager().submit(
                    new LargeCheckpointJob(triggerFile.toAbsolutePath().toString(), ackFile.toAbsolutePath().toString()), 
                    1, 512); // Needs 512MB RAM for 100MB array
            String jobId = handle.jobId();

            assertTrue(ClusterHarness.await(10_000, () -> {
                JobState state = submitter.api().getProcessManager().status(jobId);
                return state == JobState.RUNNING;
            }), "Job should transition to RUNNING");

            AegisNode leader = nodes.stream()
                    .filter(n -> n.consensus().isLeader())
                    .findFirst()
                    .orElseThrow();

            // Trigger checkpoint
            Files.write(triggerFile, new byte[]{1});

            // Wait for ack
            for (int i = 0; i < 100; i++) {
                if (Files.exists(ackFile)) break;
                Thread.sleep(50);
            }

            // IMMEDIATELY CANCEL. Worker is waiting 100ms then sending 100MB.
            // This kill will likely happen exactly during the socket transfer.
            leader.runtimeAgent().cancelJob(jobId);

            // Wait for job to become CANCELLED
            assertTrue(ClusterHarness.await(10_000, () -> {
                JobState state = submitter.api().getProcessManager().status(jobId);
                return state == JobState.CANCELLED;
            }), "Job should be CANCELLED");

            // Sleep to let dust settle
            Thread.sleep(2000);

            // Verify no partial checkpoint was written to AegisFS
            String chkPrefix = "/jobs/" + jobId + "/checkpoints/";
            List<com.aegisos.proto.FileMetadata> files = submitter.fileSystem().list(chkPrefix);
            
            // There should be 0 checkpoints, because the upload was interrupted
            assertTrue(files.isEmpty(), "No checkpoints should have been successfully saved");

            // Verify the JobRegistry does not have a checkpoint registered
            assertTrue(submitter.runtimeAgent().registry().getCheckpoint(jobId).isEmpty(),
                    "JobRegistry should not have a checkpoint registered");

            Files.deleteIfExists(triggerFile);
            Files.deleteIfExists(ackFile);
        }
    }
}

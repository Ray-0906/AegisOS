package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JobLogPersistenceTest {

    @Test
    void testJobLogUpload() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode submitter = nodes.get(0);

            // Submit SleepJob with 1000ms duration. It has printlns which go to stdout.
            JobHandle handle = submitter.api().getProcessManager().submit(new SleepJob(1000), 1, 128);
            String jobId = handle.jobId();

            // Wait for completion
            assertTrue(ClusterHarness.await(15_000, () -> {
                JobState state = submitter.api().getProcessManager().status(jobId);
                return state == JobState.COMPLETED;
            }), "Job should transition to COMPLETED");

            // Retrieve the job record to get the execution ID
            JobRecord record = submitter.runtimeAgent().registry().get(jobId).orElseThrow();
            long executionId = record.getExecutionId();
            
            assertTrue(executionId > 0, "Execution ID should be assigned");

            // Logs are uploaded after completion, wait a little just in case
            Thread.sleep(1000);

            // Read the log file from AegisFS
            String stdoutPath = "/jobs/" + jobId + "/" + executionId + "/stdout";
            byte[] data = submitter.fileSystem().read(stdoutPath);
            assertNotNull(data, "Stdout log file should exist");
            assertTrue(data.length > 0, "Stdout log file should not be empty");
            
            String stdoutStr = new String(data, StandardCharsets.UTF_8);
            assertTrue(stdoutStr.contains("SleepJob started: " + jobId), 
                    "Stdout should contain started message: " + stdoutStr);
            assertTrue(stdoutStr.contains("SleepJob finished: " + jobId), 
                    "Stdout should contain finished message: " + stdoutStr);
        }
    }
}

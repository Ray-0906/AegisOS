package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TerminalStateOrderingTest {

    public static class CrashingJob implements AegisJob<String> {
        @Override
        public String execute(JobContext ctx) throws Exception {
            throw new RuntimeException("Intentional crash");
        }
    }

    @Test
    public void testFailedStateDiscardedOnUploadDelay() throws Exception {
        // Force lease duration to be 5 seconds
        System.setProperty("aegis.lease.duration.ms", "5000");
        // Force log upload to hang for 35 seconds (guarantees requeue finishes before FAILED update)
        System.setProperty("aegis.test.delay_upload_logs", "35000");

        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            AegisNode submitter = nodes.get(0);

            // 1. Submit the job
            JobHandle h = submitter.api().getProcessManager().submit(new CrashingJob(), 1, 128);
            String jobId = h.jobId();

            // 2. Wait for 10 seconds.
            // Before the fix: worker crashes -> log upload hangs for 6s.
            // Lease (2s) expires -> JobSupervisor marks LOST -> requeues -> executionId=2 (QUEUED).
            // Log upload finishes -> update(FAILED) is discarded. State remains QUEUED.
            // After the fix: worker crashes -> update(FAILED) succeeds -> log upload hangs.
            // JobSupervisor ignores FAILED jobs. State remains FAILED.
            Thread.sleep(10000);

            // 4. Verify that the FAILED state was persisted correctly
            JobState state = submitter.api().getProcessManager().status(jobId);
            assertEquals(JobState.FAILED, state, "FAILED state should have been persisted despite log upload delay");
        } finally {
            System.clearProperty("aegis.lease.duration.ms");
            System.clearProperty("aegis.test.delay_upload_logs");
        }
    }
}

package com.aegisos.cluster;

import com.aegisos.api.ProcessManager;
import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobSpec;
import com.aegisos.runtime.Serialization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
public class WorkspaceCleanupTest {

    private ClusterHarness cluster;

    @BeforeEach
    void setup() {
        cluster = new ClusterHarness();
        cluster.setWorkspaceCleanupDelaySeconds(1); // 1 second for fast test
    }

    @AfterEach
    void teardown() {
        if (cluster != null) cluster.close();
    }

    @Test
    void testWorkspaceCleanedUpAfterGracePeriod() throws Exception {
        cluster.start(3);
        AegisNode node = cluster.node(0);
        ProcessManager pm = node.api().getProcessManager();

        String jobId = UUID.randomUUID().toString();
        com.aegisos.api.JobHandle handle = pm.submitJob(JobSpec.newBuilder()
                .setJobId(jobId)
                .setClassName(SleepJob.class.getName())
                .setArgs(com.google.protobuf.ByteString.copyFrom(Serialization.serialize(new SleepJob(10)))) // sleep 10ms
                .build());

        pm.awaitResult(handle, 10_000);
        
        com.aegisos.proto.JobRecord record = node.runtimeAgent().registry().get(jobId).orElseThrow();
        assertEquals(com.aegisos.proto.JobState.COMPLETED, record.getState());

        boolean deleted = ClusterHarness.await(5000, () -> {
            for (AegisNode n : cluster.nodes()) {
                Path er = n.config().workspaceDir().resolve(jobId).resolve("exec-" + record.getExecutionId());
                if (Files.exists(er)) return false;
            }
            return true;
        });
        assertTrue(deleted, "Workspace directory was not deleted after grace period");
    }
}

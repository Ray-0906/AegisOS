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
public class ScratchIsolationTest {

    private ClusterHarness cluster;

    @BeforeEach
    void setup() {
        cluster = new ClusterHarness();
    }

    @AfterEach
    void teardown() {
        if (cluster != null) cluster.close();
    }

    @Test
    void testScratchIsolationBetweenJobs() throws Exception {
        cluster.start(3);
        AegisNode node = cluster.node(0);
        ProcessManager pm = node.api().getProcessManager();

        String job1Id = UUID.randomUUID().toString();
        String job2Id = UUID.randomUUID().toString();

        com.aegisos.api.JobHandle handle1 = pm.submitJob(JobSpec.newBuilder()
                .setJobId(job1Id)
                .setClassName(SleepJob.class.getName())
                .setArgs(com.google.protobuf.ByteString.copyFrom(Serialization.serialize(new SleepJob(100))))
                .build());

        com.aegisos.api.JobHandle handle2 = pm.submitJob(JobSpec.newBuilder()
                .setJobId(job2Id)
                .setClassName(SleepJob.class.getName())
                .setArgs(com.google.protobuf.ByteString.copyFrom(Serialization.serialize(new SleepJob(100))))
                .build());

        pm.awaitResult(handle1, 30_000);
        pm.awaitResult(handle2, 30_000);

        com.aegisos.proto.JobRecord record1 = node.runtimeAgent().registry().get(job1Id).orElseThrow();
        com.aegisos.proto.JobRecord record2 = node.runtimeAgent().registry().get(job2Id).orElseThrow();

        assertEquals(com.aegisos.proto.JobState.COMPLETED, record1.getState());
        assertEquals(com.aegisos.proto.JobState.COMPLETED, record2.getState());

        Path execRoot1 = null;
        Path execRoot2 = null;

        for (AegisNode n : cluster.nodes()) {
            Path er1 = n.config().workspaceDir().resolve(job1Id).resolve("exec-" + record1.getExecutionId());
            if (Files.exists(er1)) execRoot1 = er1;
            
            Path er2 = n.config().workspaceDir().resolve(job2Id).resolve("exec-" + record2.getExecutionId());
            if (Files.exists(er2)) execRoot2 = er2;
        }

        assertNotNull(execRoot1, "Workspace for job 1 should exist (cleanup delay is default 300s)");
        assertNotNull(execRoot2, "Workspace for job 2 should exist");

        assertNotEquals(execRoot1.toAbsolutePath().toString(), execRoot2.toAbsolutePath().toString());
        
        Path scratch1 = execRoot1.resolve("scratch");
        Path scratch2 = execRoot2.resolve("scratch");
        
        assertNotEquals(scratch1.toAbsolutePath().toString(), scratch2.toAbsolutePath().toString());
    }
}

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
public class ArtifactNotFoundTest {

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
    void testJobFailsWhenArtifactMissingFromStorage() throws Exception {
        cluster.start(3);
        AegisNode node = cluster.node(0);
        ProcessManager pm = node.api().getProcessManager();

        // 1. Secretly insert a fake artifact into the registry (bypassing AegisFS)
        String sha256 = "0000000000000000000000000000000000000000000000000000000000000000";
        com.aegisos.proto.ArtifactRecord ar = com.aegisos.proto.ArtifactRecord.newBuilder()
                .setArtifactId(sha256)
                .setSize(100)
                .setCreatedAt(System.currentTimeMillis())
                .build();
                
        com.aegisos.proto.RegisterArtifact regCmd = com.aegisos.proto.RegisterArtifact.newBuilder().setArtifact(ar).build();
        node.consensus().propose(com.aegisos.proto.StateCommand.newBuilder()
                .setType(com.aegisos.proto.CommandType.REGISTER_ARTIFACT)
                .setPayload(regCmd.toByteString())
                .build()).get(5, java.util.concurrent.TimeUnit.SECONDS);

        // 3. Submit job that requests the artifact
        String jobId = UUID.randomUUID().toString();
        com.aegisos.api.JobHandle handle = pm.submitJob(JobSpec.newBuilder()
                .setJobId(jobId)
                .setClassName(SleepJob.class.getName())
                .setArgs(com.google.protobuf.ByteString.copyFrom(Serialization.serialize(new SleepJob(10))))
                .addArtifacts(com.aegisos.proto.ArtifactReference.newBuilder()
                        .setSha256(sha256)
                        .setMountPath("missing.bin")
                        .build())
                .build());

        // 4. Job should fail (awaitResult throws RuntimeException on failure)
        Exception e = assertThrows(RuntimeException.class, () -> pm.awaitResult(handle, 10_000));
        
        com.aegisos.proto.JobRecord record = node.runtimeAgent().registry().get(jobId).orElseThrow();
        assertEquals(com.aegisos.proto.JobState.FAILED, record.getState());
        
        // 5. Error should mention artifact or download
        assertTrue(record.getError().toLowerCase().contains("artifact") || 
                   record.getError().toLowerCase().contains("download") ||
                   record.getError().toLowerCase().contains("not found") ||
                   record.getError().toLowerCase().contains("no such file") ||
                   e.getMessage().toLowerCase().contains("artifact") ||
                   e.getMessage().toLowerCase().contains("no such file"));
    }
}

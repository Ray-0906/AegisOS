package com.aegisos.cluster;

import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.proto.ArtifactReference;
import com.aegisos.proto.JobSpec;
import com.aegisos.proto.JobState;
import com.aegisos.proto.ResourceRequest;
import com.aegisos.runtime.Serialization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class MountPathTraversalTest {

    private ClusterHarness cluster;

    @BeforeEach
    public void setup() throws Exception {
        cluster = new ClusterHarness();
        cluster.start(3);
    }

    @AfterEach
    public void teardown() throws Exception {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    public void testRejectsPathTraversal() throws Exception {
        byte[] dummyData = "dummy artifact".getBytes();
        String sha256 = cluster.node(0).api().getProcessManager().uploadArtifact(dummyData);

        String jobId = java.util.UUID.randomUUID().toString();
        JobSpec spec = JobSpec.newBuilder()
                .setJobId(jobId)
                .setClassName(SleepJob.class.getName())
                .setArgs(com.google.protobuf.ByteString.copyFrom(Serialization.serialize(new SleepJob(100))))
                .setResources(ResourceRequest.newBuilder().setCpuCores(1).setMemoryMb(128).build())
                .addArtifacts(ArtifactReference.newBuilder()
                        .setSha256(sha256)
                        .setMountPath("../escaped.bin")
                        .build())
                .build();

        com.aegisos.api.JobHandle handle = cluster.node(0).api().getProcessManager().submitJob(spec);
        try {
            cluster.node(0).api().getProcessManager().awaitResult(handle, 10_000);
        } catch (Exception e) {
            // expected to fail
        }

        com.aegisos.proto.JobRecord record = cluster.node(0).runtimeAgent().registry().get(jobId).orElseThrow();
        assertEquals(JobState.FAILED, record.getState());
        assertTrue(record.getError() != null && (record.getError().contains("Invalid mount path") || record.getError().contains("escapes artifacts directory")), "Actual error: " + record.getError());
    }

    @Test
    public void testRejectsAbsolutePath() throws Exception {
        byte[] dummyData = "dummy artifact".getBytes();
        String sha256 = cluster.node(0).api().getProcessManager().uploadArtifact(dummyData);

        String jobId = UUID.randomUUID().toString();
        JobSpec spec = JobSpec.newBuilder()
                .setJobId(jobId)
                .setClassName(SleepJob.class.getName())
                .setArgs(com.google.protobuf.ByteString.copyFrom(Serialization.serialize(new SleepJob(100))))
                .setResources(ResourceRequest.newBuilder().setCpuCores(1).setMemoryMb(128).build())
                .addArtifacts(ArtifactReference.newBuilder()
                        .setSha256(sha256)
                        .setMountPath("/etc/passwd")
                        .build())
                .build();

        com.aegisos.api.JobHandle handle = cluster.node(0).api().getProcessManager().submitJob(spec);
        try {
            cluster.node(0).api().getProcessManager().awaitResult(handle, 10_000);
        } catch (Exception e) {
            // expected
        }

        com.aegisos.proto.JobRecord record = cluster.node(0).runtimeAgent().registry().get(jobId).orElseThrow();
        assertEquals(JobState.FAILED, record.getState());
        assertTrue(record.getError() != null && (record.getError().contains("Invalid mount path") || record.getError().contains("escapes artifacts directory")), "Actual error: " + record.getError());
    }
}

package com.aegisos.cluster;

import com.aegisos.api.ProcessManager;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobSpec;
import com.aegisos.proto.ArtifactReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import java.util.UUID;
import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.runtime.Serialization;
import com.aegisos.proto.JobRecord;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
public class ArtifactCacheReuseTest {

    private ClusterHarness harness;

    @BeforeEach
    void setup() {
        harness = new ClusterHarness();
    }

    @AfterEach
    void teardown() {
        if (harness != null) harness.close();
    }

    @Test
    void testArtifactIsCachedAndReused() throws Exception {
        harness.start(3);
        AegisNode node = harness.node(0);
        ProcessManager pm = node.api().getProcessManager();

        // Upload Artifact A
        byte[] artDataA = "Dummy Artifact A Content".getBytes(StandardCharsets.UTF_8);
        String shaA = pm.uploadArtifact(artDataA);

        // Submit Job 1 using Artifact A
        String jobId1 = UUID.randomUUID().toString();
        com.aegisos.api.JobHandle handle1 = pm.submitJob(JobSpec.newBuilder()
                .setJobId(jobId1)
                .setClassName(SleepJob.class.getName())
                .setArgs(com.google.protobuf.ByteString.copyFrom(Serialization.serialize(new SleepJob(100))))
                .addArtifacts(ArtifactReference.newBuilder().setSha256(shaA).setMountPath("a.txt").build())
                .build());

        // Wait for Job 1
        pm.awaitResult(handle1, 10_000);
        JobRecord rec1 = node.runtimeAgent().registry().get(jobId1).orElseThrow();
        assertEquals(com.aegisos.proto.JobState.COMPLETED, rec1.getState(), "Job 1 did not complete");

        // Verify Artifact A is cached locally on the assigned node
        AegisNode assignedNode = harness.nodes().stream()
                .filter(n -> com.google.protobuf.ByteString.copyFrom(n.identity().nodeId().toBytes()).equals(rec1.getAssignedNodeId()))
                .findFirst().orElseThrow();
        Path cachePathA = assignedNode.config().artifactCacheDir().resolve(shaA + ".jar");
        assertTrue(Files.exists(cachePathA), "Artifact A not found in cache after Job 1");
        long mtime1 = Files.getLastModifiedTime(cachePathA).toMillis();

        // Submit Job 2 using Artifact A
        String jobId2 = UUID.randomUUID().toString();
        com.aegisos.api.JobHandle handle2 = pm.submitJob(JobSpec.newBuilder()
                .setJobId(jobId2)
                .setClassName(SleepJob.class.getName())
                .setArgs(com.google.protobuf.ByteString.copyFrom(Serialization.serialize(new SleepJob(100))))
                .addArtifacts(ArtifactReference.newBuilder().setSha256(shaA).setMountPath("a.txt").build())
                .build());

        // Wait for Job 2
        pm.awaitResult(handle2, 10_000);
        JobRecord rec2 = node.runtimeAgent().registry().get(jobId2).orElseThrow();
        assertEquals(com.aegisos.proto.JobState.COMPLETED, rec2.getState(), "Job 2 did not complete");

        // Verify Artifact A is still cached and mtime might be unchanged or updated (touched)
        assertTrue(Files.exists(cachePathA), "Artifact A not found in cache after Job 2");

        // Assert Locality: Job 2 should be scheduled on the SAME node as Job 1 because it has the artifact cached
        assertEquals(rec1.getAssignedNodeId(), rec2.getAssignedNodeId(), 
            "Scheduler failed to place Job 2 on the node with cached Artifact A");
    }
}

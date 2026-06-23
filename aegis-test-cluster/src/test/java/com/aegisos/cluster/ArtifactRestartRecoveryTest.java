package com.aegisos.cluster;

import com.aegisos.api.ProcessManager;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobSpec;
import com.aegisos.proto.ArtifactReference;
import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.runtime.Serialization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.aegisos.testing.ClusterAwaiter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
public class ArtifactRestartRecoveryTest {

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
    void testArtifactCacheRecoveryOnRestart() throws Exception {
        cluster.setReplicationFactor(1);
        cluster.start(1);
        
        AegisNode node = cluster.node(0);
        ClusterAwaiter awaiter = new ClusterAwaiter(cluster);
        awaiter.awaitWriteReady(node, Duration.ofSeconds(10));
        
        ProcessManager pm = node.api().getProcessManager();

        byte[] data = new byte[]{1, 2, 3};
        String sha256 = pm.uploadArtifact(data);
        assertNotNull(sha256);

        com.aegisos.api.JobHandle handle1 = pm.submitJob(JobSpec.newBuilder()
                .setJobId(UUID.randomUUID().toString())
                .setClassName(SleepJob.class.getName())
                .setArgs(com.google.protobuf.ByteString.copyFrom(Serialization.serialize(new SleepJob(1))))
                .addArtifacts(ArtifactReference.newBuilder().setSha256(sha256).setMountPath("a.txt").build())
                .build());
        pm.awaitResult(handle1, 10_000);

        Path cachePathA = node.config().artifactCacheDir().resolve(sha256 + ".jar");
        assertTrue(Files.exists(cachePathA), "Artifact not found in cache");

        node.close();

        AegisNode newNode = cluster.restartNode(node);
        // ClusterAwaiter already instantiated
        
        // Wait for the data plane to recover and serve the artifact
        awaiter.awaitArtifactReadable(newNode, sha256, Duration.ofSeconds(10));
        
        // Verify via API download (already verified implicitly by awaiter, but good for test correctness)
        byte[] data2 = newNode.api().getProcessManager().downloadArtifact(sha256);
        assertArrayEquals(data, data2);
    }
}

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

import java.nio.file.Files;
import java.nio.file.Path;
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
        // Start a single node to make it easy to restart
        cluster.setReplicationFactor(1);
        cluster.start(1);
        
        // Wait for leader election
        Thread.sleep(2000);
        AegisNode node = cluster.node(0);
        ProcessManager pm = node.api().getProcessManager();

        byte[] data = new byte[]{1, 2, 3};
        String sha256 = pm.uploadArtifact(data);
        assertNotNull(sha256);

        // Force cache it by putting it in cache manually (or downloading)
        // Submit a job to force artifact caching
        com.aegisos.api.JobHandle handle1 = pm.submitJob(JobSpec.newBuilder()
                .setJobId(UUID.randomUUID().toString())
                .setClassName(SleepJob.class.getName())
                .setArgs(com.google.protobuf.ByteString.copyFrom(Serialization.serialize(new SleepJob(10))))
                .addArtifacts(ArtifactReference.newBuilder().setSha256(sha256).setMountPath("a.txt").build())
                .build());
        pm.awaitResult(handle1, 10_000);

        // Verify it is cached locally on node
        Path cachePathA = node.config().artifactCacheDir().resolve(sha256 + ".jar");
        assertTrue(Files.exists(cachePathA), "Artifact not found in cache");

        // Shut down the node
        node.close();

        // Restart a new node with the SAME data dir
        AegisNode newNode = cluster.restartNode(node);
        
        // Let it bootstrap and recover and elect leader
        Thread.sleep(3000);

        // The cache should still contain the file
        Path newCachePathA = newNode.config().artifactCacheDir().resolve(sha256 + ".jar");
        assertTrue(Files.exists(newCachePathA), "Cached file should still exist on disk after restart");

        // Use API to download it, it should work normally
        byte[] data2 = newNode.api().getProcessManager().downloadArtifact(sha256);
        assertArrayEquals(data, data2);
    }
}

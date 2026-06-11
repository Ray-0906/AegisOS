package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.CheckpointableSum;
import com.aegisos.proto.FileMetadata;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class CheckpointPersistenceTest {

    private static ClusterHarness cluster;

    @BeforeAll
    static void setup() throws Exception {
        cluster = new ClusterHarness();
        cluster.start(3);
        ClusterHarness.await(10000, () -> cluster.nodes().stream()
                .allMatch(n -> n.consensus().leaderId() != null));
    }

    @AfterAll
    static void teardown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void testCheckpointsAreWrittenAndRetained() throws Exception {
        var node = cluster.nodes().get(0);

        // Submit job with target=10, step=100ms. Will checkpoint 10 times.
        JobHandle handle = node.api().getProcessManager().submit(
                new CheckpointableSum(10, 100), 1, 512);

        Object result = node.api().getProcessManager().awaitResult(handle, 10000);
        assertEquals(55L, result, "Sum of 1..10 should be 55");

        // Wait a bit for Raft apply of checkpoints to finish
        Thread.sleep(500);

        // Verify checkpoints in JobRegistry
        var checkpoint = node.runtimeAgent().registry().getCheckpoint(handle.jobId());
        assertTrue(checkpoint.isPresent(), "JobCheckpointRecord should exist");
        assertEquals(handle.jobId(), checkpoint.get().jobId());
        assertTrue(checkpoint.get().metadata().getSequence() > 0, "Should have a positive sequence number");
        assertEquals("/jobs/" + handle.jobId() + "/checkpoints/chk-" + checkpoint.get().metadata().getSequence(), checkpoint.get().checkpointFileId());

        // Wait for retention policy to delete old checkpoints (max 5)
        assertTrue(ClusterHarness.await(5000, () -> {
            try {
                List<FileMetadata> files = node.fileSystem().list("/jobs/" + handle.jobId() + "/checkpoints/");
                return !files.isEmpty() && files.size() <= 5;
            } catch (Exception e) {
                return false;
            }
        }), "Retention policy should cap checkpoints at 5");
    }
}

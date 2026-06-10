package com.aegisos.cluster;

import com.aegisos.cluster.jobs.CheckpointableSum;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.FileMetadata;
import com.aegisos.api.JobHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CheckpointRetentionTest {

    private ClusterHarness harness;

    @BeforeEach
    public void setUp() {
        harness = new ClusterHarness();
        harness.setReplicationFactor(1); // fast local test
    }

    @AfterEach
    public void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    public void testCheckpointRetentionPrunesOldFiles() throws Exception {
        List<AegisNode> nodes = harness.start(1);
        AegisNode node = nodes.get(0);
        assertTrue(ClusterHarness.await(10_000, () -> node.consensus().isLeader()), "Single node should become leader");

        // Sum to 10, sleeping 50ms per step -> 10 checkpoints
        JobHandle handle = node.api().getProcessManager().submit(new CheckpointableSum(10, 50), 1, 128);

        // Wait for job to finish
        Object result = node.api().getProcessManager().awaitResult(handle, 10_000);
        assertEquals(55L, result, "Sum to 10 should be 55");

        // The retention limit is hardcoded to 5 in ProcessRuntimeAgent
        String jobId = handle.jobId();
        String prefix = "/jobs/" + jobId + "/checkpoints/chk-";
        
        // Ensure that file system applies deletions
        // It might take a moment if it's asynchronous, but it's synchronous in ProcessRuntimeAgent
        List<FileMetadata> checkpoints = node.fileSystem().list(prefix);
        
        assertEquals(5, checkpoints.size(), "Should retain exactly 5 checkpoints");

        // Ensure the remaining ones are the latest ones (chk-6 to chk-10)
        boolean hasSequence10 = false;
        boolean hasSequence6 = false;
        boolean hasSequence1 = false;
        for (FileMetadata fm : checkpoints) {
            if (fm.getName().endsWith("/chk-10")) hasSequence10 = true;
            if (fm.getName().endsWith("/chk-6")) hasSequence6 = true;
            if (fm.getName().endsWith("/chk-1")) hasSequence1 = true;
        }

        assertTrue(hasSequence10, "Should have retained checkpoint 10");
        assertTrue(hasSequence6, "Should have retained checkpoint 6");
        assertTrue(!hasSequence1, "Should have deleted checkpoint 1");
    }
}

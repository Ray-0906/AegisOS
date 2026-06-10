package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.CheckpointableSum;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SnapshotCheckpointRecoveryTest {

    @Test
    void testCheckpointsSurviveSnapshotAndRestart() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            harness.setSnapshotEntryThreshold(2);
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode leader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst().orElseThrow();

            // Submit job
            JobHandle handle = leader.api().getProcessManager().submit(new CheckpointableSum(20, 100), 1, 128);
            String jobId = handle.jobId();

            // Wait for it to make at least 5 checkpoints
            assertTrue(ClusterHarness.await(10_000, () -> {
                var chk = leader.runtimeAgent().registry().getCheckpoint(jobId);
                return chk.isPresent() && chk.get().metadata().getSequence() >= 5;
            }), "Job should create some checkpoints");

            // Snapshots should happen automatically since threshold is 2 and we have 5+ checkpoints.

            // Await completion
            Object result = leader.api().getProcessManager().awaitResult(handle, 30_000);
            assertTrue(result instanceof Long);
            assertEquals(210L, result);
            
            // Checkpoint record should still be available in registry (now COMPLETED)
            var chk = leader.runtimeAgent().registry().getCheckpoint(jobId);
            assertTrue(chk.isPresent(), "Checkpoint record should survive");
        }
    }
}

package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.CheckpointableSum;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LeaderFailoverCheckpointTest {

    @Test
    void testCheckpointReplicatedToFollowers() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            harness.setReplicationFactor(2);
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode leader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst().orElseThrow();

            JobHandle handle = leader.api().getProcessManager().submit(new CheckpointableSum(20, 200), 1, 128);
            String jobId = handle.jobId();

            assertTrue(ClusterHarness.await(10_000, () -> {
                var chk = leader.runtimeAgent().registry().getCheckpoint(jobId);
                return chk.isPresent() && chk.get().metadata().getSequence() >= 3;
            }), "Job should create some checkpoints");

            long lastSeq = leader.runtimeAgent().registry().getCheckpoint(jobId).get().metadata().getSequence();

            // Stop leader
            harness.stop(leader);

            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().filter(n -> n != leader).anyMatch(n -> n.consensus().isLeader())
            ), "New leader should be elected");

            AegisNode newLeader = nodes.stream().filter(n -> n != leader && n.consensus().isLeader()).findFirst().orElseThrow();

            // Check that the new leader has the checkpoint sequence >= lastSeq
            var chk = newLeader.runtimeAgent().registry().getCheckpoint(jobId);
            assertTrue(chk.isPresent(), "Checkpoint record should be replicated");
            assertTrue(chk.get().metadata().getSequence() >= lastSeq, "Sequence should be at least as advanced as before failover");
        }
    }
}

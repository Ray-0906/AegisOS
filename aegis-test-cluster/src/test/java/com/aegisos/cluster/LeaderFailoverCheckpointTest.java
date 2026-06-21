package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.CheckpointableSum;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import com.aegisos.testing.ClusterAwaiter;

public class LeaderFailoverCheckpointTest {

    @Test
    void testCheckpointReplicatedToFollowers() throws Exception {
        // Fast failure simulation for the runtime lease
        System.setProperty("aegis.lease.duration.ms", "2000");
        System.setProperty("aegis.supervisor.interval.ms", "1000");
        
        try (ClusterHarness harness = new ClusterHarness()) {
            harness.setReplicationFactor(2);
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode leader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst().orElseThrow();

            JobHandle handle = leader.api().getProcessManager().submit(new CheckpointableSum(20, 200), 1, 128);
            String jobId = handle.jobId();

            ClusterAwaiter awaiter = new ClusterAwaiter(harness);

            awaiter.awaitCheckpointVisible(jobId, 3, Duration.ofSeconds(10));

            long lastSeq = leader.runtimeAgent().registry().getCheckpoint(jobId).get().metadata().getSequence();
            com.aegisos.core.identity.NodeId leaderId = leader.identity().nodeId();

            // Stop leader
            harness.stop(leader);

            // 1. Await Leader Election (Raft)
            awaiter.awaitLeaderElection(Duration.ofSeconds(45));
            
            // 2. Await Checkpoint Visible (Runtime/Raft Sync)
            awaiter.awaitCheckpointVisible(jobId, lastSeq, Duration.ofSeconds(20));
            
            // 3. Await Checkpoint Restored (Runtime Failover)
            // Wait for the job to resume and write a NEW checkpoint
            awaiter.awaitCheckpointVisible(jobId, lastSeq + 1, Duration.ofSeconds(45));
            
            // 4. Await Node Death (Discovery) - moved to the end
            awaiter.awaitNodeDeath(leaderId, Duration.ofSeconds(45));
        } finally {
            System.clearProperty("aegis.lease.duration.ms");
            System.clearProperty("aegis.supervisor.interval.ms");
        }
    }
}

package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.CheckpointableSum;
import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class LocalityMetricsValidationTest {

    @Test
    public void testCheckpointLocalityWins() throws Exception {
        System.setProperty("aegis.reservation.ttl", "2000"); // faster tests
        System.setProperty("aegis.lease.duration.ms", "5000"); // faster timeouts for failure
        try (ClusterHarness harness = new ClusterHarness()) {
            harness.setReplicationFactor(2);
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode submitter = nodes.get(0);
            
            // Initial job running
            JobHandle handle = submitter.api().getProcessManager().submit(new CheckpointableSum(50, 100), 1, 128);
            String jobId = handle.jobId();

            assertTrue(ClusterHarness.await(10_000, () -> {
                var chk = submitter.runtimeAgent().registry().getCheckpoint(jobId);
                return chk.isPresent() && chk.get().metadata().getSequence() >= 1;
            }), "Job should create some checkpoints");

            NodeId executorId = submitter.runtimeAgent().registry().get(jobId)
                    .map(JobRecord::getAssignedNodeId)
                    .filter(b -> !b.isEmpty())
                    .map(b -> NodeId.of(b.toByteArray())).orElseThrow();

            // Wait for metrics to be collected
            AegisNode leader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst().orElseThrow();
            
            long winsBefore = leader.scheduler().getLocalityWins();

            // Isolate executor to trigger migration
            com.aegisos.network.NetworkLayer.setMessageFilter((from, to) -> {
                if (from.equals(executorId) || to.equals(executorId)) return false;
                return true;
            });

            AegisNode aliveNode = harness.nodes().stream()
                    .filter(n -> !n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();

            // Wait for job to migrate and run again
            assertTrue(ClusterHarness.await(25_000, () -> {
                var job = aliveNode.runtimeAgent().registry().get(jobId);
                return job.isPresent() && job.get().getExecutionId() >= 2 && job.get().getState() == JobState.RUNNING;
            }), "Job should migrate and resume");

            long winsAfter = leader.scheduler().getLocalityWins();
            assertTrue(winsAfter > winsBefore || leader.scheduler().getTotalDownloadBytesSaved() > 0, 
                       "Scheduler should have favored nodes with local checkpoints. winsAfter: " + winsAfter + ", bytes: " + leader.scheduler().getTotalDownloadBytesSaved());

            com.aegisos.network.NetworkLayer.clearMessageFilter();
        } finally {
            System.clearProperty("aegis.reservation.ttl");
            System.clearProperty("aegis.lease.duration.ms");
            com.aegisos.network.NetworkLayer.clearMessageFilter();
        }
    }
}

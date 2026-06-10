package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(120)
public class ExecutionRecoveryAfterSnapshotTest {

    @Test
    public void testExecutionRecoveryAfterSnapshot() throws Exception {
        System.setProperty("aegis.snapshot.entryThreshold", "5");
        System.setProperty("aegis.lease.duration.ms", "15000");
        try (ClusterHarness harness = new ClusterHarness()) {
            harness.setSnapshotEntryThreshold(5);
            harness.setReplicationFactor(2);
            List<AegisNode> nodes = harness.start(3);

            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode leader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst().get();

            JobHandle handle = leader.api().getProcessManager().submit(new SleepJob(30_000), 1, 128);
            String jobId = handle.jobId();

            assertTrue(ClusterHarness.await(15_000, () -> 
                leader.api().getProcessManager().status(jobId) == JobState.RUNNING
            ), "Job should start RUNNING");

            // Write 7 files to force snapshot
            for (int i = 0; i < 7; i++) {
                leader.fileSystem().write("snap-trigger-" + i, new byte[]{(byte)i});
            }

            assertTrue(ClusterHarness.await(15_000, () -> 
                nodes.stream().anyMatch(n -> n.consensus().raftNode().raftLog().snapshotIndex() > 0)
            ), "Snapshot should be taken");

            // Find worker
            NodeId executorId = assignedNode(leader, jobId).orElseThrow();
            AegisNode executor = nodes.stream()
                    .filter(n -> n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();
                    
            // Stop the executor
            harness.stop(executor);

            AegisNode aliveNode = harness.nodes().stream()
                    .filter(n -> !n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();

            // Wait for LOST -> QUEUED -> RUNNING -> COMPLETED
            assertTrue(ClusterHarness.await(90_000, () -> 
                aliveNode.api().getProcessManager().status(jobId) == JobState.COMPLETED
            ), "Job should eventually COMPLETE");

            long completedCount = aliveNode.runtimeAgent().registry()
                .all().stream()
                .filter(r -> r.getSpec().getJobId().equals(jobId)
                          && r.getState() == JobState.COMPLETED)
                .count();
            assertEquals(1L, completedCount, "Exactly one execution must reach COMPLETED");

            long executionId = aliveNode.runtimeAgent().registry().get(jobId).get().getExecutionId();
            assertTrue(executionId >= 2, "Execution ID should be >= 2");
            
            // Wait a moment for async log upload
            Thread.sleep(2000);
            
            // Check logs
            var metaOpt2 = aliveNode.fileSystem().fileIndex().byName("/jobs/" + jobId + "/" + executionId + "/stdout");
            assertTrue(metaOpt2.isPresent(), "Stdout for recovery execution should exist");
        } finally {
            System.clearProperty("aegis.snapshot.entryThreshold");
            System.clearProperty("aegis.lease.duration.ms");
        }
    }

    private static Optional<NodeId> assignedNode(AegisNode node, String jobId) {
        return node.runtimeAgent().registry().get(jobId)
                .map(JobRecord::getAssignedNodeId)
                .filter(b -> !b.isEmpty())
                .map(b -> NodeId.of(b.toByteArray()));
    }
}

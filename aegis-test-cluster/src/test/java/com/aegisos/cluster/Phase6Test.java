package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.CheckpointableSum;
import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 6 gate: a long-running, checkpointed job survives the death of the node executing
 * it -- it migrates to another node and still produces the correct result.
 */
class Phase6Test {

    @Test
    void runningJobSurvivesNodeDeath() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(5);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 5)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));
            Thread.sleep(1_000);

            AegisNode submitter = nodes.get(0);
            int target = 80;
            long expected = (long) target * (target + 1) / 2;

            // ~8s job, checkpointing every ~1s.
            JobHandle handle = submitter.api().getProcessManager().submit(
                    new CheckpointableSum(target, 100), 1, 512);
            String jobId = handle.jobId();

            // Wait until the job is assigned and running on some node.
            assertTrue(ClusterHarness.await(8_000, () ->
                    assignedNode(submitter, jobId).isPresent()));
            NodeId executor = assignedNode(submitter, jobId).orElseThrow();

            // Let it run a bit so at least one checkpoint is persisted, then kill the executor.
            Thread.sleep(2_500);
            AegisNode victim = nodes.stream()
                    .filter(n -> n.identity().nodeId().equals(executor)).findFirst().orElseThrow();
            harness.stop(victim);

            // A surviving node observes the migrated job complete with the correct result.
            AegisNode observer = harness.nodes().stream()
                    .filter(n -> !n.identity().nodeId().equals(executor)).findFirst().orElseThrow();
            Long result = observer.api().getProcessManager().awaitResult(handle, 90_000);
            assertEquals(expected, result, "migrated job must produce the correct result");

            // The job ended up assigned to a node other than the one we killed.
            NodeId finalNode = assignedNode(observer, jobId).orElseThrow();
            assertNotEquals(executor, finalNode, "job should have migrated to a different node");
        }
    }

    private static Optional<NodeId> assignedNode(AegisNode node, String jobId) {
        return node.runtimeAgent().registry().get(jobId)
                .map(JobRecord::getAssignedNodeId)
                .map(b -> NodeId.of(b.toByteArray()));
    }
}

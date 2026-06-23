package com.aegisos.cluster;



import com.aegisos.api.JobHandle;

import com.aegisos.cluster.jobs.CheckpointableSum;

import com.aegisos.core.identity.NodeId;

import com.aegisos.node.AegisNode;

import com.aegisos.proto.JobRecord;

import com.aegisos.proto.JobState;

import org.junit.jupiter.api.Test;



import java.util.HashMap;

import java.util.List;

import java.util.Map;

import java.util.Optional;



import static org.junit.jupiter.api.Assertions.assertNotEquals;

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



            Map<NodeId, Integer> winsBeforeByNode = snapshotLocalityWins(nodes);



            com.aegisos.network.NetworkLayer.setMessageFilter((from, to, type, payload) -> {

                // Drop messages between submitter and worker2quals(executorId)) return false;

                if (from.equals(executorId) || to.equals(executorId)) return false;

                return true;

            });



            assertTrue(ClusterHarness.await(25_000, () ->

                            resolveMigratedJob(nodes, executorId, jobId).isPresent()),

                    "Job should migrate and resume");



            JobRecord migrated = resolveMigratedJob(nodes, executorId, jobId).orElseThrow();

            NodeId assignedNode = NodeId.of(migrated.getAssignedNodeId().toByteArray());

            String checkpointPath = migrated.getCheckpointFileId();



            assertNotEquals(executorId, assignedNode,

                    "Migrated job should not remain on the partitioned executor");

            assertTrue(!checkpointPath.isEmpty(), "Migrated job should retain checkpoint path");



            AegisNode assignedHost = findNode(nodes, assignedNode);

            long localityBytes = assignedHost.runtimeAgent().getDownloadBytesSaved(List.of(), checkpointPath);

            assertTrue(localityBytes > 0,

                    "Migrated job should land on a node with local checkpoint replicas; assigned="

                            + assignedNode.shortId() + " bytesSaved=" + localityBytes);



            // Supplementary: metrics belong to whichever non-partitioned node is currently leading

            // and performed the reschedule — not the leader captured before partition.

            AegisNode schedulingLeader = resolveSchedulingLeader(nodes, executorId);

            int winsBefore = winsBeforeByNode.getOrDefault(schedulingLeader.identity().nodeId(), 0);

            int winsAfter = schedulingLeader.scheduler().getLocalityWins();

            long bytesSavedAfter = schedulingLeader.scheduler().getTotalDownloadBytesSaved();

            assertTrue(winsAfter > winsBefore || bytesSavedAfter > 0,

                    "Scheduling leader should record locality benefit after migration. leader="

                            + schedulingLeader.identity().nodeId().shortId()

                            + " winsBefore=" + winsBefore + " winsAfter=" + winsAfter

                            + " bytesSaved=" + bytesSavedAfter);



            com.aegisos.network.NetworkLayer.clearMessageFilter();

        } finally {

            TestJvmHygiene.clearAll();

        }

    }



    /** Authoritative post-migration view from nodes that can still observe the cluster. */

    private static Optional<JobRecord> resolveMigratedJob(List<AegisNode> nodes, NodeId executorId, String jobId) {

        for (AegisNode node : nodes) {

            if (node.identity().nodeId().equals(executorId)) {

                continue;

            }

            Optional<JobRecord> job = node.runtimeAgent().registry().get(jobId);

            if (job.isPresent()

                    && job.get().getExecutionId() >= 2

                    && job.get().getState() == JobState.RUNNING) {

                return job;

            }

        }

        return Optional.empty();

    }



    /** Active leader among nodes that remain connected after partitioning the executor. */

    private static AegisNode resolveSchedulingLeader(List<AegisNode> nodes, NodeId partitionedExecutor) {

        return nodes.stream()

                .filter(n -> !n.identity().nodeId().equals(partitionedExecutor))

                .filter(n -> n.consensus().isLeader())

                .findFirst()

                .orElseThrow(() -> new IllegalStateException(

                        "No leader found among non-partitioned nodes"));

    }



    private static Map<NodeId, Integer> snapshotLocalityWins(List<AegisNode> nodes) {

        Map<NodeId, Integer> wins = new HashMap<>();

        for (AegisNode node : nodes) {

            wins.put(node.identity().nodeId(), node.scheduler().getLocalityWins());

        }

        return wins;

    }



    private static AegisNode findNode(List<AegisNode> nodes, NodeId nodeId) {

        return nodes.stream()

                .filter(n -> n.identity().nodeId().equals(nodeId))

                .findFirst()

                .orElseThrow(() -> new IllegalStateException("Node not found: " + nodeId.shortId()));

    }

}



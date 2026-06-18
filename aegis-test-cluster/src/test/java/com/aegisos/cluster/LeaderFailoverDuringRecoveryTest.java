package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.aegisos.testing.ClusterAwaiter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(120)
public class LeaderFailoverDuringRecoveryTest {

    // --- TASK-005: State timeline tracker ---

    private static final class StateSnapshot {
        final long timestampMs;
        final long elapsedMs;
        final JobState state;
        final long executionId;
        final String assignedNodeId;
        final String leaderNodeId;
        final String source; // which observation point captured this

        StateSnapshot(long timestampMs, long elapsedMs, JobState state, long executionId,
                      String assignedNodeId, String leaderNodeId, String source) {
            this.timestampMs = timestampMs;
            this.elapsedMs = elapsedMs;
            this.state = state;
            this.executionId = executionId;
            this.assignedNodeId = assignedNodeId;
            this.leaderNodeId = leaderNodeId;
            this.source = source;
        }

        @Override
        public String toString() {
            return String.format("T+%05dms %s(exec=%d) assigned=%s leader=%s [%s]",
                elapsedMs, state, executionId, assignedNodeId, leaderNodeId, source);
        }
    }

    @Test
    public void testLeaderFailoverDuringRecovery() throws Exception {
        System.setProperty("aegis.lease.duration.ms", "15000"); 
        System.setProperty("aegis.test.delay_after_lost", "true");

        // TASK-005: timeline
        final List<StateSnapshot> timeline = new CopyOnWriteArrayList<>();
        final long testStartMs = System.currentTimeMillis();
        final AtomicBoolean testDone = new AtomicBoolean(false);

        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(5);
            ClusterAwaiter awaiter = new ClusterAwaiter(harness);

            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 5)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            AegisNode tempLeader = null;
            while (tempLeader == null) {
                tempLeader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst().orElse(null);
                if (tempLeader == null) Thread.sleep(10);
            }
            final AegisNode leader = tempLeader;

            // Use 30s sleep to prevent the job from finishing before failover happens.
            JobHandle handle = leader.api().getProcessManager().submit(new SleepJob(30_000), 1, 128);
            String jobId = handle.jobId();

            // TASK-005: record SUBMITTED
            recordSnapshot(timeline, testStartMs, harness, nodes, jobId, "SUBMITTED");

            // TASK-005: start background poller
            Thread poller = new Thread(() -> {
                JobState lastState = null;
                long lastExecId = -1;
                while (!testDone.get()) {
                    try {
                        Thread.sleep(250); // poll every 250ms
                        StateSnapshot snap = captureSnapshot(testStartMs, harness, nodes, jobId, "poll");
                        if (snap != null && (snap.state != lastState || snap.executionId != lastExecId)) {
                            timeline.add(snap);
                            lastState = snap.state;
                            lastExecId = snap.executionId;
                        }
                    } catch (Exception e) {
                        // ignore polling errors during shutdown
                    }
                }
            }, "TASK005-poller");
            poller.setDaemon(true);
            poller.start();

            assertTrue(ClusterHarness.await(15_000, () -> 
                leader.api().getProcessManager().status(jobId) == JobState.RUNNING
            ), "Job should start RUNNING");

            recordSnapshot(timeline, testStartMs, harness, nodes, jobId, "RUNNING_confirmed");

            NodeId executorId = assignedNode(leader, jobId).orElseThrow();
            AegisNode executor = nodes.stream()
                    .filter(n -> n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();
                    
            // Stop the executor
            recordSnapshot(timeline, testStartMs, harness, nodes, jobId, "pre_stop_executor");
            harness.stop(executor);
            recordSnapshot(timeline, testStartMs, harness, nodes, jobId, "post_stop_executor");

            if (executor != leader) {
                // Wait for leader to emit LOST (Max time: lease 15s + supervisor 15s = 30s)
                assertTrue(ClusterHarness.await(45_000, () -> 
                    leader.api().getProcessManager().status(jobId) == JobState.LOST
                ), "Job should transition to LOST");

                recordSnapshot(timeline, testStartMs, harness, nodes, jobId, "LOST_confirmed");

                // Stop the leader immediately after it emits LOST, but before it requeues
                // (delay_after_lost hook pauses it for 5s)
                harness.stop(leader);
            }

            // 1 Awaiter: Wait for the job to be recovered by the NEW leader's JobSupervisor
            awaiter.awaitJobRecovered(jobId, Duration.ofSeconds(90));
        } finally {
            testDone.set(true);
            System.clearProperty("aegis.lease.duration.ms");
            System.clearProperty("aegis.test.delay_after_lost");
        }
    }

    // --- TASK-005 helpers ---

    private void recordSnapshot(List<StateSnapshot> timeline, long testStartMs,
                                ClusterHarness harness, List<AegisNode> nodes,
                                String jobId, String source) {
        StateSnapshot snap = captureSnapshot(testStartMs, harness, nodes, jobId, source);
        if (snap != null) {
            timeline.add(snap);
        }
    }

    private StateSnapshot captureSnapshot(long testStartMs, ClusterHarness harness,
                                          List<AegisNode> nodes, String jobId, String source) {
        long now = System.currentTimeMillis();
        // Try leader first, then any alive node
        AegisNode observerNode = harness.currentLeader();
        if (observerNode == null) {
            for (AegisNode n : harness.nodes()) {
                try {
                    if (n.runtimeAgent().registry().get(jobId).isPresent()) {
                        observerNode = n;
                        break;
                    }
                } catch (Exception e) { /* skip dead nodes */ }
            }
        }
        if (observerNode == null) return null;

        try {
            Optional<JobRecord> rec = observerNode.runtimeAgent().registry().get(jobId);
            if (rec.isEmpty()) return null;
            JobRecord r = rec.get();
            String assignedId = r.getAssignedNodeId().isEmpty() ? "none"
                    : NodeId.of(r.getAssignedNodeId().toByteArray()).shortId();
            AegisNode leaderNode = harness.currentLeader();
            String leaderId = leaderNode != null ? leaderNode.identity().nodeId().shortId() : "NONE";
            return new StateSnapshot(now, now - testStartMs, r.getState(),
                    r.getExecutionId(), assignedId, leaderId, source);
        } catch (Exception e) {
            return null;
        }
    }

    private String compactTimeline(List<StateSnapshot> timeline) {
        StringBuilder sb = new StringBuilder();
        for (StateSnapshot snap : timeline) {
            if (sb.length() > 0) sb.append(" → ");
            sb.append(snap.state).append("(e").append(snap.executionId).append(",T+").append(snap.elapsedMs).append("ms)");
        }
        return sb.toString();
    }

    private static Optional<NodeId> assignedNode(AegisNode node, String jobId) {
        return node.runtimeAgent().registry().get(jobId)
                .map(JobRecord::getAssignedNodeId)
                .filter(b -> !b.isEmpty())
                .map(b -> NodeId.of(b.toByteArray()));
    }
}

package com.aegisos.testing;

import com.aegisos.cluster.ClusterHarness;
import com.aegisos.core.identity.NodeId;
import com.aegisos.proto.JobState;
import com.aegisos.node.AegisNode;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

public class ClusterAwaiter {

    private final ClusterHarness harness;

    public ClusterAwaiter(ClusterHarness harness) {
        this.harness = harness;
    }

    public void awaitLeaderElection(Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> {
            for (AegisNode node : harness.nodes()) {
                if (node.consensus().isLeader()) {
                    return true;
                }
            }
            return false;
        });
    }

    public void awaitQuorum(Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> {
            for (AegisNode node : harness.nodes()) {
                if (node.consensus().isLeader()) {
                    int alive = node.discovery().membership().aliveCount();
                    if (alive >= harness.nodes().size() / 2 + 1) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    public void awaitJobState(String jobId, JobState state, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> {
            for (AegisNode node : harness.nodes()) {
                if (node.consensus().isLeader()) {
                    var job = node.runtimeAgent().registry().get(jobId);
                    if (job.isPresent() && job.get().getState() == state) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    public void awaitReplication(String jobId, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> {
            for (AegisNode node : harness.nodes()) {
                if (node.consensus().isLeader()) {
                    var job = node.runtimeAgent().registry().get(jobId);
                    return job.isPresent();
                }
            }
            return false;
        });
    }

    public void awaitWorkerLeaseExpiration(NodeId nodeId, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> {
            for (AegisNode node : harness.nodes()) {
                if (node.consensus().isLeader()) {
                    var allJobs = node.runtimeAgent().registry().all();
                    boolean hadJobs = false;
                    for (var j : allJobs) {
                        if (!j.getAssignedNodeId().isEmpty()) {
                            NodeId assigned = NodeId.of(j.getAssignedNodeId().toByteArray());
                            if (nodeId.equals(assigned)) {
                                hadJobs = true;
                                if (j.getState() == JobState.RUNNING || j.getState() == JobState.QUEUED) {
                                    return false; // Still active
                                }
                            }
                        }
                    }
                    return hadJobs; // Lease expired on all its jobs
                }
            }
            return false;
        });
    }

    public void awaitNodeDeath(NodeId nodeId, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> {
            for (AegisNode node : harness.nodes()) {
                if (node.consensus().isLeader()) {
                    return node.discovery().membership().statusOf(nodeId) == com.aegisos.proto.PeerStatus.DEAD;
                }
            }
            return false;
        });
    }
}

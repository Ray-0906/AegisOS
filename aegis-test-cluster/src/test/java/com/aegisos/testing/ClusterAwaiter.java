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
        new EventAwaiter().withTimeout(timeout).await(() -> harness.currentLeader() != null);
    }

    public void awaitQuorum(Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> harness.hasQuorum());
    }

    public void awaitJobState(String jobId, JobState state, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> harness.getJobState(jobId) == state);
    }

    public void awaitReplication(String jobId, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> harness.isJobPresent(jobId));
    }

    public void awaitArtifactReplication(String artifactId, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> harness.isArtifactReplicated(artifactId));
    }

    public void awaitWorkerLeaseExpiration(NodeId nodeId, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> harness.isWorkerLeaseExpired(nodeId));
    }

    public void awaitNodeDeath(NodeId nodeId, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> harness.isNodeDead(nodeId));
    }
}

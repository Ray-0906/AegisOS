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

    public void awaitCheckpointCreated(String jobId, int minSequence, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> harness.hasCheckpoint(jobId, minSequence));
    }

    public void awaitPendingRepair(String repairId, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> harness.hasPendingRepair(repairId));
    }

    public void awaitRepairTaskVisible(String repairId, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> harness.hasRepairTask(repairId));
    }

    public void awaitCheckpointVisible(String jobId, long minSeq, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> harness.hasCheckpoint(jobId, minSeq));
    }

    public void awaitNodeReady(AegisNode node, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(node::isReady);
    }

    public void awaitWriteReady(AegisNode node, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(node::isWriteReady);
    }

    public void awaitArtifactReadable(AegisNode node, String sha256, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> harness.isArtifactReadable(node, sha256));
    }

    public void awaitJobRecovered(String jobId, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> harness.isJobRecovered(jobId));
    }

    public void awaitRepairCompletion(String repairId, Duration timeout) throws TimeoutException, InterruptedException {
        new EventAwaiter().withTimeout(timeout).await(() -> harness.isRepairComplete(repairId));
    }
}

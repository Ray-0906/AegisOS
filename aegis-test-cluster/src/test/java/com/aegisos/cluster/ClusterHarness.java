package com.aegisos.cluster;

import com.aegisos.core.model.Endpoint;
import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.node.NodeConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Spins up N AegisOS nodes in-process on ephemeral ports for integration and chaos tests.
 * Node 0 is the bootstrap/seed; all later nodes seed from it.
 */
public final class ClusterHarness implements AutoCloseable {

    private final List<AegisNode> nodes = new ArrayList<>();
    private final List<Path> tempDirs = new ArrayList<>();
    private Endpoint seedEndpoint;
    private int replicationFactor = 3;
    private int snapshotEntryThreshold = 1000;
    private int workspaceCleanupDelaySeconds = 300; // default 5m
    private int repairTaskTimeoutSeconds = 300; // default 5m

    public ClusterHarness() {
        // H6 instrumentation: set current test name for event correlation
        String testName = "unknown";
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if (frame.getClassName().endsWith("Test") && !frame.getClassName().equals(getClass().getName())) {
                testName = frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1);
                break;
            }
        }
        com.aegisos.consensus.RaftLagMonitor.currentTestName = testName;
        StatsTracker.dump("TEST_BEGIN", java.util.Collections.emptyList());
        System.out.println("BEGIN_TIMESTAMP=" + System.currentTimeMillis());
    }

    public void setRepairTaskTimeoutSeconds(int seconds) {
        this.repairTaskTimeoutSeconds = seconds;
    }

    public void setWorkspaceCleanupDelaySeconds(int delay) {
        this.workspaceCleanupDelaySeconds = delay;
    }

    public void setSnapshotEntryThreshold(int threshold) {
        this.snapshotEntryThreshold = threshold;
    }

    public void setReplicationFactor(int rf) {
        this.replicationFactor = rf;
    }

    /** Starts {@code n} nodes and returns them. The first node is the seed. */
    public List<AegisNode> start(int n) throws IOException {
        long t0 = System.currentTimeMillis();
        Thread monitor = new Thread(() -> {
            long leaderTime = -1;
            long discoverTime = -1;
            while (leaderTime == -1 || discoverTime == -1) {
                if (leaderTime == -1 && currentLeader() != null) {
                    leaderTime = System.currentTimeMillis() - t0;
                    System.out.println("LEADER_ELECTED=" + leaderTime);
                }
                if (discoverTime == -1) {
                    try {
                        boolean all = nodes.size() == n;
                        if (all) {
                            for (AegisNode node : nodes) {
                                if (node.discovery().membership().aliveCount() < n) {
                                    all = false; break;
                                }
                            }
                            if (all) {
                                discoverTime = System.currentTimeMillis() - t0;
                                System.out.println("ALL_NODES_DISCOVERED=" + discoverTime);
                            }
                        }
                    } catch (java.util.ConcurrentModificationException ignored) {}
                }
                try { Thread.sleep(10); } catch (Exception e) { break; }
            }
        });
        monitor.start();

        for (int i = 0; i < n; i++) {
            addNode();
        }

        System.out.println("BOOT_DURATION=" + (System.currentTimeMillis() - t0));
        
        try { monitor.join(1000); } catch (Exception e){}
        return List.copyOf(nodes);
    }

    /**
     * Adds and starts one more node.
     *
     * <p>Seeds the new node from every currently-alive node in the cluster, not just
     * the original bootstrap node. This is critical for chaos tests: if the original
     * bootstrap seed was killed earlier, a naive {@code seedEndpoint} reference would
     * produce a node with zero gossip visibility that loops elections indefinitely.
     * By advertising all surviving nodes as seeds, the replacement always joins the
     * live cluster regardless of which nodes have been killed.
     */
    public AegisNode addNode() throws IOException {
        Path home = Files.createTempDirectory("aegis-node-");
        tempDirs.add(home);
        return addNodeWithHome(home);
    }

    public AegisNode addNodeWithHome(Path home) throws IOException {
        NodeConfig config = new NodeConfig()
                .homeDir(home)
                .port(0)
                .advertiseHost("127.0.0.1")
                .reaperIntervalMs(2_000)
                .checkpointIntervalMs(1_000)
                .replicationFactor(replicationFactor)
                .snapshotEntryThreshold(snapshotEntryThreshold)
                .jobSupervisorEnabled(jobSupervisorEnabled)
                .repairEnabled(repairEnabled)
                .auditIntervalSeconds(2)
                .workspaceCleanupDelaySeconds(workspaceCleanupDelaySeconds)
                .repairTaskTimeoutSeconds(repairTaskTimeoutSeconds);

        boolean isBootstrap = nodes.isEmpty() && seedEndpoint == null;
        config.bootstrap(isBootstrap);

        // Prefer alive nodes as seeds; fall back to the original bootstrap seed only
        // if no nodes are alive yet (i.e. this is the very first node being started).
        if (!nodes.isEmpty()) {
            for (AegisNode alive : nodes) {
                config.addSeed(new Endpoint("127.0.0.1", alive.network().boundPort()));
            }
        } else if (seedEndpoint != null) {
            config.addSeed(seedEndpoint);
        }

        AegisNode node = new AegisNode(config);
        StatsTracker.NODE_COUNT.incrementAndGet();
        node.start();
        if (seedEndpoint == null) {
            // Record the first node's address as the canonical bootstrap seed
            // (used as fallback only when nodes list is empty).
            seedEndpoint = new Endpoint("127.0.0.1", node.network().boundPort());
        }
        nodes.add(node);

        if (!isBootstrap) {
            try {
                AegisNode leaderNode = awaitLeader(10_000);
                if (leaderNode == null) {
                    throw new IllegalStateException("No leader found to add node as voter");
                }

                boolean joinedGossip = await(30_000, () -> {
                    AegisNode leaderSeeingNode = currentLeaderSeeing(node);
                    return leaderSeeingNode != null;
                });
                if (!joinedGossip) {
                    AegisNode currentLeader = awaitLeader(1_000);
                    String leaderId = currentLeader == null ? "none" : currentLeader.identity().nodeId().shortId();
                    throw new IllegalStateException("New node " + node.identity().nodeId().shortId() + " did not join Gossip on any leader within 30s (current leader " + leaderId + ")");
                }

                boolean caughtUp = await(30_000, () -> {
                    AegisNode currentLeader = currentLeaderSeeing(node);
                    if (currentLeader == null) {
                        return false;
                    }
                    long leaderLast = currentLeader.consensus().raftNode().lastLogIndex();
                    long nodeMatch = currentLeader.consensus().raftNode().matchIndex(node.identity().nodeId());
                    return (leaderLast - nodeMatch) <= 10;
                });
                if (!caughtUp) {
                    throw new IllegalStateException("New node " + node.identity().nodeId().shortId() + " did not catch up within 30s");
                }

                // Leader proposes ADD_VOTER
                com.aegisos.proto.StateCommand addCmd = com.aegisos.proto.StateCommand.newBuilder()
                        .setType(com.aegisos.proto.CommandType.ADD_VOTER)
                        .setPayload(com.google.protobuf.ByteString.copyFrom(node.identity().nodeId().toBytes()))
                        .build();
                proposeOnCurrentLeader(addCmd, node);

                // Wait for the new node to actually apply the ADD_VOTER command and become a voter locally
                boolean appliedLocally = await(30_000, () -> node.consensus().clusterConfiguration().isVoter(node.identity().nodeId()));
                if (!appliedLocally) {
                    throw new IllegalStateException("New node " + node.identity().nodeId().shortId() + " did not apply ADD_VOTER locally within 30s");
                }
            } catch (Exception e) {
                throw new IOException("Failed to add node " + node.identity().nodeId().shortId() + " to cluster", e);
            }
        }
        return node;
    }

    private AegisNode awaitLeader(long timeoutMs) throws InterruptedException {
        final AegisNode[] found = new AegisNode[1];
        await(timeoutMs, () -> {
            found[0] = currentLeader();
            return found[0] != null;
        });
        return found[0];
    }

    public AegisNode currentLeader() {
        for (AegisNode existing : nodes) {
            if (existing.consensus().isLeader()) {
                return existing;
            }
        }
        return null;
    }

    public boolean hasQuorum() {
        AegisNode leader = currentLeader();
        if (leader != null) {
            int alive = leader.discovery().membership().aliveCount();
            return alive >= nodes.size() / 2 + 1;
        }
        return false;
    }

    public com.aegisos.proto.JobState getJobState(String jobId) {
        AegisNode leader = currentLeader();
        if (leader != null) {
            var job = leader.runtimeAgent().registry().get(jobId);
            if (job.isPresent()) {
                return job.get().getState();
            }
        }
        return null;
    }

    public boolean isJobPresent(String jobId) {
        AegisNode leader = currentLeader();
        if (leader != null) {
            return leader.runtimeAgent().registry().get(jobId).isPresent();
        }
        return false;
    }

    public boolean hasCheckpoint(String jobId, int minSequence) {
        AegisNode leader = currentLeader();
        if (leader != null) {
            var chk = leader.runtimeAgent().registry().getCheckpoint(jobId);
            return chk.isPresent() && chk.get().metadata().getSequence() >= minSequence;
        }
        return false;
    }

    public boolean hasPendingRepair(String repairId) {
        AegisNode leader = currentLeader();
        if (leader != null) {
            return leader.fileSystem().repairTaskStore().pendingByRepairId(repairId).isPresent();
        }
        return false;
    }

    public boolean hasRepairTask(String repairId) {
        AegisNode leader = currentLeader();
        if (leader != null) {
            return leader.fileSystem().repairTaskStore().all().stream()
                    .anyMatch(t -> t.repairId().equals(repairId));
        }
        return false;
    }

    public boolean hasCheckpoint(String jobId, long minSeq) {
        AegisNode leader = currentLeader();
        if (leader != null) {
            var chk = leader.runtimeAgent().registry().getCheckpoint(jobId);
            return chk.isPresent() && chk.get().metadata().getSequence() >= minSeq;
        }
        return false;
    }

    public boolean isArtifactReadable(AegisNode node, String sha256) {
        try {
            return node.api().getProcessManager().downloadArtifact(sha256) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isJobRecovered(String jobId) {
        AegisNode leader = currentLeader();
        if (leader != null) {
            var record = leader.runtimeAgent().registry().get(jobId).orElse(null);
            if (record != null) {
                boolean isRunningOrCompleted = record.getState() == com.aegisos.proto.JobState.RUNNING 
                                            || record.getState() == com.aegisos.proto.JobState.COMPLETED;
                return isRunningOrCompleted && record.getExecutionId() >= 2;
            }
        }
        return false;
    }

    public boolean isRepairComplete(String repairId) {
        AegisNode leader = currentLeader();
        if (leader != null) {
            var all = leader.fileSystem().repairTaskStore().all();
            boolean match = all.stream()
                    .anyMatch(t -> t.repairId().equals(repairId) && 
                            t.status() == com.aegisos.fs.audit.RepairTaskStore.TaskStatus.COMPLETE);
            if (!match) {
                System.out.println("isRepairComplete FALSE. Current tasks on leader " + leader.identity().nodeId().shortId() + ":");
                for (var t : all) {
                    System.out.println("  - " + t.repairId() + " " + t.status());
                }
            }
            return match;
        }
        return false;
    }

    public boolean isArtifactReplicated(String artifactId) {
        for (AegisNode node : nodes) {
            boolean hasIt = node.artifactRegistry().listAll().stream()
                    .anyMatch(a -> a.getArtifactId().equals(artifactId));
            if (!hasIt) {
                return false;
            }
        }
        return true;
    }

    public boolean isWorkerLeaseExpired(NodeId nodeId) {
        AegisNode leader = currentLeader();
        if (leader != null) {
            var allJobs = leader.runtimeAgent().registry().all();
            boolean hadJobs = false;
            for (var j : allJobs) {
                if (!j.getAssignedNodeId().isEmpty()) {
                    NodeId assigned = NodeId.of(j.getAssignedNodeId().toByteArray());
                    if (nodeId.equals(assigned)) {
                        hadJobs = true;
                        if (j.getState() == com.aegisos.proto.JobState.RUNNING || j.getState() == com.aegisos.proto.JobState.QUEUED) {
                            return false; // Still active
                        }
                    }
                }
            }
            return hadJobs; // Lease expired on all its jobs
        }
        return false;
    }

    public boolean isNodeDead(NodeId nodeId) {
        AegisNode leader = currentLeader();
        if (leader != null) {
            return leader.discovery().membership().statusOf(nodeId) == com.aegisos.proto.PeerStatus.DEAD;
        }
        return false;
    }

    private AegisNode currentLeaderSeeing(AegisNode node) {
        NodeId nodeId = node.identity().nodeId();
        for (AegisNode existing : nodes) {
            if (existing == node || !existing.consensus().isLeader()) {
                continue;
            }
            com.aegisos.proto.PeerStatus status = existing.discovery().membership().statusOf(nodeId);
            if (status == com.aegisos.proto.PeerStatus.ALIVE || status == com.aegisos.proto.PeerStatus.SUSPECT) {
                return existing;
            }
        }
        return null;
    }

    private void proposeOnCurrentLeader(com.aegisos.proto.StateCommand command, AegisNode joiningNode) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            AegisNode currentLeader = currentLeaderSeeing(joiningNode);
            if (currentLeader != null) {
                try {
                    currentLeader.consensus().propose(command).get(10, java.util.concurrent.TimeUnit.SECONDS);
                    return;
                } catch (Exception e) {
                    lastFailure = e;
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Failed to propose ADD_VOTER on a stable leader", lastFailure);
    }


    public List<AegisNode> nodes() {
        return nodes;
    }

    public AegisNode node(int i) {
        return nodes.get(i);
    }

    public AegisNode restartNode(AegisNode oldNode) throws IOException {
        com.aegisos.node.NodeConfig oldConfig = oldNode.config();
        AegisNode newNode = new AegisNode(new com.aegisos.node.NodeConfig()
                .homeDir(oldConfig.homeDir())
                .port(0)
                .advertiseHost(oldConfig.advertiseHost())
                .reaperIntervalMs(oldConfig.reaperIntervalMs())
                .checkpointIntervalMs(oldConfig.checkpointIntervalMs())
                .replicationFactor(oldConfig.replicationFactor())
                .snapshotEntryThreshold(oldConfig.snapshotEntryThreshold())
                .jobSupervisorEnabled(oldConfig.jobSupervisorEnabled())
                .repairEnabled(oldConfig.repairEnabled())
                .auditIntervalSeconds(oldConfig.auditIntervalSeconds())
                .repairTaskTimeoutSeconds(oldConfig.repairTaskTimeoutSeconds())
                .bootstrap(oldConfig.bootstrap()));
        
        if (!newNode.config().bootstrap()) {
            if (!nodes.isEmpty()) {
                newNode.config().addSeed(new Endpoint("127.0.0.1", nodes.get(0).network().boundPort()));
            } else if (seedEndpoint != null) {
                newNode.config().addSeed(seedEndpoint);
            }
        }
        
        newNode.start();
        
        if (newNode.config().bootstrap()) {
            seedEndpoint = new Endpoint("127.0.0.1", newNode.network().boundPort());
        }
        
        nodes.add(newNode);
        return newNode;
    }

    private boolean autoRemoveVoters = false;
    private boolean jobSupervisorEnabled = true;
    private boolean repairEnabled = true;

    public ClusterHarness setJobSupervisorEnabled(boolean enabled) {
        this.jobSupervisorEnabled = enabled;
        return this;
    }

    public ClusterHarness setRepairEnabled(boolean enabled) {
        this.repairEnabled = enabled;
        return this;
    }

    public void setAutoRemoveVoters(boolean auto) {
        this.autoRemoveVoters = auto;
    }

    public void stop(AegisNode node) {
        NodeId stoppedId = node.identity().nodeId();
        node.close();
        nodes.remove(node);

        if (autoRemoveVoters && !nodes.isEmpty()) {
            try {
                // Find leader of remaining nodes
                AegisNode leaderNode = null;
                for (int attempt = 0; attempt < 200; attempt++) {
                    for (AegisNode existing : nodes) {
                        if (existing.consensus().isLeader()) {
                            leaderNode = existing;
                            break;
                        }
                    }
                    if (leaderNode != null) {
                        break;
                    }
                    Thread.sleep(50);
                }
                if (leaderNode != null) {
                    com.aegisos.proto.StateCommand removeCmd = com.aegisos.proto.StateCommand.newBuilder()
                            .setType(com.aegisos.proto.CommandType.REMOVE_VOTER)
                            .setPayload(com.google.protobuf.ByteString.copyFrom(stoppedId.toBytes()))
                            .build();
                    leaderNode.consensus().propose(removeCmd).get(10, java.util.concurrent.TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                System.out.println("[WARN] Failed to automatically remove stopped node " + stoppedId.shortId() + " from voters: " + e.getMessage());
            }
        }
    }

    /** Polls a condition until true or the deadline elapses. */
    public static boolean await(long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    @Override
    public void close() {
        System.out.println("END_TIMESTAMP=" + System.currentTimeMillis());
        StatsTracker.dump("TEST_END", nodes);
        for (AegisNode node : nodes) {
            try {
                node.close();
            } catch (Exception ignored) {
            }
        }
        nodes.clear();
        for (Path dir : tempDirs) {
            deleteRecursive(dir.toFile());
        }
        tempDirs.clear();
    }

    private static boolean deleteRecursive(java.io.File f) {
        if (!f.exists()) {
            return true;
        }
        boolean success = true;
        java.io.File[] children = f.listFiles();
        if (children != null) {
            for (java.io.File c : children) {
                success &= deleteRecursive(c);
            }
        }
        boolean deleted = f.delete();
        return success && deleted;
    }
}

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
        for (int i = 0; i < n; i++) {
            addNode();
        }
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
                .workspaceCleanupDelaySeconds(workspaceCleanupDelaySeconds);

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
        node.start();
        if (seedEndpoint == null) {
            // Record the first node's address as the canonical bootstrap seed
            // (used as fallback only when nodes list is empty).
            seedEndpoint = new Endpoint("127.0.0.1", node.network().boundPort());
        }
        nodes.add(node);

        if (!isBootstrap) {
            try {
                // Find leader node
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
                if (leaderNode == null) {
                    throw new IllegalStateException("No leader found to add node as voter");
                }

                final AegisNode finalLeader = leaderNode;
                // Wait for Gossip to discover the new node and mark it alive
                boolean joinedGossip = await(30_000, () -> {
                    com.aegisos.proto.PeerStatus status = finalLeader.discovery().membership().statusOf(node.identity().nodeId());
                    return status == com.aegisos.proto.PeerStatus.ALIVE || status == com.aegisos.proto.PeerStatus.SUSPECT;
                });
                if (!joinedGossip) {
                    throw new IllegalStateException("New node " + node.identity().nodeId().shortId() + " did not join Gossip on leader " + finalLeader.identity().nodeId().shortId() + " within 30s");
                }

                // Wait for replicator to catch up (so lag is <= 10)
                boolean caughtUp = await(30_000, () -> {
                    long leaderLast = finalLeader.consensus().raftNode().lastLogIndex();
                    long nodeMatch = finalLeader.consensus().raftNode().matchIndex(node.identity().nodeId());
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
                finalLeader.consensus().propose(addCmd).get(30, java.util.concurrent.TimeUnit.SECONDS);

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

    private static void deleteRecursive(java.io.File f) {
        java.io.File[] children = f.listFiles();
        if (children != null) {
            for (java.io.File c : children) {
                deleteRecursive(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}

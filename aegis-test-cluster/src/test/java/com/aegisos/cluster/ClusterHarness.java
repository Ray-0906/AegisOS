package com.aegisos.cluster;

import com.aegisos.core.model.Endpoint;
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

    /** Starts {@code n} nodes and returns them. The first node is the seed. */
    public List<AegisNode> start(int n) throws IOException {
        for (int i = 0; i < n; i++) {
            addNode();
        }
        return List.copyOf(nodes);
    }

    /** Adds and starts one more node, seeded from node 0 (if any). */
    public AegisNode addNode() throws IOException {
        Path home = Files.createTempDirectory("aegis-node-");
        tempDirs.add(home);
        NodeConfig config = new NodeConfig()
                .homeDir(home)
                .port(0)
                .advertiseHost("127.0.0.1")
                .reaperIntervalMs(2_000)
                .checkpointIntervalMs(1_000);
        if (seedEndpoint != null) {
            config.addSeed(seedEndpoint);
        }
        AegisNode node = new AegisNode(config);
        node.start();
        if (seedEndpoint == null) {
            seedEndpoint = new Endpoint("127.0.0.1", node.network().boundPort());
        }
        nodes.add(node);
        return node;
    }

    public List<AegisNode> nodes() {
        return nodes;
    }

    public AegisNode node(int i) {
        return nodes.get(i);
    }

    public void stop(AegisNode node) {
        node.close();
        nodes.remove(node);
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

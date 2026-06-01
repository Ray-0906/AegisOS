package com.aegisos.cli.commands;

import com.aegisos.core.model.Endpoint;
import com.aegisos.node.AegisNode;
import com.aegisos.node.NodeConfig;
import com.aegisos.proto.PeerEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Shared helpers for CLI subcommands that talk to a running cluster. Each helper boots a
 * transient client node, connects to the cluster via the given seeds, performs the
 * operation through the in-process API, then shuts down.
 *
 * <p>Implementations are completed as the underlying layers land (Phase 2: nodes,
 * Phase 4: put/get/ls, Phase 5: run/status).
 */
final class ClientCommands {

    private ClientCommands() {
    }

    /** Boots a transient client node joined to the cluster, runs {@code fn}, then shuts down. */
    static <T> T withClient(List<String> seeds, Function<AegisNode, T> fn) throws Exception {
        Path home = Files.createTempDirectory("aegis-cli-");
        NodeConfig config = new NodeConfig().homeDir(home).port(0).advertiseHost("127.0.0.1");
        for (String s : seeds) {
            config.addSeed(Endpoint.parse(s));
        }
        AegisNode node = new AegisNode(config);
        node.start();
        try {
            // Give gossip and Raft a moment to converge (max 5 seconds).
            for (int i = 0; i < 100; i++) {
                if (node.discovery().membership().aliveCount() > 1 && node.consensus().leaderId() != null) {
                    break;
                }
                Thread.sleep(50);
            }
            return fn.apply(node);
        } finally {
            node.close();
            deleteRecursive(home.toFile());
        }
    }

    static int runNodes(List<String> seeds) {
        if (seeds.isEmpty()) {
            System.err.println("aegis nodes: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                System.out.printf("%-14s %-22s %-8s%n", "NODE", "ADDRESS", "STATUS");
                for (PeerEntry p : node.discovery().membership().allPeers()) {
                    String id = com.aegisos.core.util.HexUtil.shortId(p.getNodeId().toByteArray());
                    System.out.printf("%-14s %-22s %-8s%n", id, p.getAddress(), p.getStatus());
                }
                return 0;
            });
        } catch (Exception e) {
            System.err.println("aegis nodes failed: " + e.getMessage());
            return 1;
        }
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

    static int runPut(List<String> seeds, String local, String remote) {
        if (seeds.isEmpty()) {
            System.err.println("aegis put: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                try {
                    byte[] data = java.nio.file.Files.readAllBytes(Path.of(local));
                    node.fileSystem().write(remote, data);
                    System.out.println("Uploaded " + local + " -> " + remote + " (" + data.length + " bytes)");
                    return 0;
                } catch (Exception e) {
                    System.err.println("put failed: " + e.getMessage());
                    return 1;
                }
            });
        } catch (Exception e) {
            System.err.println("aegis put failed: " + e.getMessage());
            return 1;
        }
    }

    static int runGet(List<String> seeds, String remote, String local) {
        if (seeds.isEmpty()) {
            System.err.println("aegis get: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                try {
                    // Wait for the file metadata to replicate to this client node.
                    for (int i = 0; i < 100 && node.fileSystem().fileIndex().byName(remote).isEmpty(); i++) {
                        Thread.sleep(50);
                    }
                    byte[] data = node.fileSystem().read(remote);
                    java.nio.file.Files.write(Path.of(local), data);
                    System.out.println("Downloaded " + remote + " -> " + local + " (" + data.length + " bytes)");
                    return 0;
                } catch (Exception e) {
                    System.err.println("get failed: " + e.getMessage());
                    return 1;
                }
            });
        } catch (Exception e) {
            System.err.println("aegis get failed: " + e.getMessage());
            return 1;
        }
    }

    static int runLs(List<String> seeds, String path) {
        if (seeds.isEmpty()) {
            System.err.println("aegis ls: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                try {
                    Thread.sleep(500); // allow raft catch-up
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                System.out.printf("%-30s %12s %s%n", "NAME", "SIZE", "CHUNKS");
                node.fileSystem().list(path).forEach(f ->
                        System.out.printf("%-30s %12d %d%n", f.getName(), f.getSize(), f.getChunksCount()));
                return 0;
            });
        } catch (Exception e) {
            System.err.println("aegis ls failed: " + e.getMessage());
            return 1;
        }
    }

    static int runJob(List<String> seeds, String className, List<String> args) {
        if (seeds.isEmpty()) {
            System.err.println("aegis run: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                try {
                    com.aegisos.runtime.AegisJob<?> job = instantiate(className, args);
                    com.aegisos.api.JobHandle handle = node.api().getProcessManager().submit(job);
                    System.out.println("Submitted job " + handle.jobId());
                    Object result = node.api().getProcessManager().awaitResult(handle, 120_000);
                    System.out.println("Result: " + result);
                    return 0;
                } catch (Exception e) {
                    System.err.println("run failed: " + e.getMessage());
                    return 1;
                }
            });
        } catch (Exception e) {
            System.err.println("aegis run failed: " + e.getMessage());
            return 1;
        }
    }

    static int runStatus(List<String> seeds, String jobId) {
        if (seeds.isEmpty()) {
            System.err.println("aegis status: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                for (int i = 0; i < 60 && node.api().getProcessManager().status(jobId)
                        == com.aegisos.proto.JobState.JOB_UNKNOWN; i++) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                System.out.println("Job " + jobId + ": " + node.api().getProcessManager().status(jobId));
                return 0;
            });
        } catch (Exception e) {
            System.err.println("aegis status failed: " + e.getMessage());
            return 1;
        }
    }

    private static com.aegisos.runtime.AegisJob<?> instantiate(String className, List<String> args)
            throws Exception {
        Class<?> clazz = Class.forName(className);
        Object instance;
        try {
            instance = clazz.getConstructor(String[].class)
                    .newInstance((Object) args.toArray(new String[0]));
        } catch (NoSuchMethodException e) {
            instance = clazz.getDeclaredConstructor().newInstance();
        }
        if (!(instance instanceof com.aegisos.runtime.AegisJob<?> job)) {
            throw new IllegalArgumentException(className + " is not an AegisJob");
        }
        return job;
    }

    private static int notReady(String cmd) {
        System.err.println("aegis " + cmd + ": not available in this build");
        return 2;
    }
}

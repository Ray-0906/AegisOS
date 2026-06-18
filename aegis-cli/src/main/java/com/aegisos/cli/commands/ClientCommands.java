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
public final class ClientCommands {

    private ClientCommands() {
    }

    /** Boots a transient client node joined to the cluster, runs {@code fn}, then shuts down. */
    public static <T> T withClient(List<String> seeds, Function<AegisNode, T> fn) throws Exception {
        Path home = Files.createTempDirectory("aegis-cli-");
        NodeConfig config = new NodeConfig().homeDir(home).port(0).advertiseHost("127.0.0.1")
                .role(com.aegisos.proto.NodeRole.CLIENT);
        for (String s : seeds) {
            config.addSeed(Endpoint.parse(s));
        }
        AegisNode node = new AegisNode(config);
        node.start();
        try {
            // Transient clients should not accept jobs.
            node.scheduler().setAcceptProbe(() -> false);

            // Give gossip and Raft a moment to converge (max 10 seconds).
            // A transient CLI node starts with zero gossip state: it must discover
            // surviving cluster members, then receive an AppendEntries heartbeat to
            // learn who the current leader is. Under heavy node churn the old 5s
            // window was not enough, causing spurious "no known leader" errors.
            boolean ready = false;
            for (int i = 0; i < 200; i++) {
                if (node.discovery().membership().storageNodeCount() >= node.config().replicationFactor()
                        && node.consensus().leaderId() != null) {
                    ready = true;
                    break;
                }
                Thread.sleep(50);
            }
            if (!ready) {
                System.err.println("[WARN] No cluster leader detected within 10s. " +
                        "The cluster may still be re-electing after a node failure. " +
                        "Proceeding anyway — the operation may fail if no leader exists.");
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

    static int runJob(List<String> seeds, String className, List<String> args, int cpuCores, long memoryMb) {
        if (seeds.isEmpty()) {
            System.err.println("aegis run: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                try {
                    com.aegisos.runtime.AegisJob<?> job = instantiate(className, args);
                    com.aegisos.api.JobHandle handle = node.api().getProcessManager().submit(job, cpuCores, memoryMb);
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

    static int runArtifactUpload(List<String> seeds, String jarPath) {
        if (seeds.isEmpty()) {
            System.err.println("aegis artifact upload: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                try {
                    byte[] data = java.nio.file.Files.readAllBytes(Path.of(jarPath));
                    String sha256 = com.aegisos.core.util.HexUtil.encode(
                            com.aegisos.core.crypto.Hashing.sha256(data));

                    if (node.artifactRegistry().bySha256(sha256).isPresent()) {
                        System.out.println("Artifact already uploaded: " + sha256);
                        return 0;
                    }

                    String fsPath = "/artifacts/" + sha256;
                    node.fileSystem().write(fsPath, data);

                    java.io.File jarFile = new java.io.File(jarPath);
                    com.aegisos.proto.ArtifactRecord record = com.aegisos.proto.ArtifactRecord.newBuilder()
                            .setArtifactId(sha256)
                            .setFileName(jarFile.getName())
                            .setSize(data.length)
                            .setCreatedAt(System.currentTimeMillis())
                            .setFsPath(fsPath)
                            .setOwnerId(com.google.protobuf.ByteString.copyFrom(node.identity().nodeId().toBytes()))
                            .setStatus(com.aegisos.proto.ArtifactStatus.ARTIFACT_ACTIVE)
                            .build();

                    com.aegisos.proto.RegisterArtifact regCmd = com.aegisos.proto.RegisterArtifact.newBuilder()
                            .setArtifact(record)
                            .build();

                    node.consensus().propose(com.aegisos.proto.StateCommand.newBuilder()
                            .setType(com.aegisos.proto.CommandType.REGISTER_ARTIFACT)
                            .setPayload(regCmd.toByteString())
                            .build()).get(5, java.util.concurrent.TimeUnit.SECONDS);

                    System.out.println("Uploaded " + jarFile.getName() + " (artifact: " + sha256 + ", size: " + data.length + " bytes)");
                    return 0;
                } catch (Exception e) {
                    System.err.println("upload failed: " + e.getMessage());
                    return 1;
                }
            });
        } catch (Exception e) {
            System.err.println("aegis artifact upload failed: " + e.getMessage());
            return 1;
        }
    }

    static int runArtifactList(List<String> seeds) {
        if (seeds.isEmpty()) {
            System.err.println("aegis artifact list: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                try {
                    Thread.sleep(500); // allow raft catch-up
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                System.out.printf("%-64s %-30s %12s %s%n", "ARTIFACT ID", "FILE NAME", "SIZE", "STATUS");
                for (com.aegisos.proto.ArtifactRecord r : node.artifactRegistry().listAll()) {
                    System.out.printf("%-64s %-30s %12d %s%n", r.getArtifactId(), r.getFileName(), r.getSize(), r.getStatus());
                }
                return 0;
            });
        } catch (Exception e) {
            System.err.println("aegis artifact list failed: " + e.getMessage());
            return 1;
        }
    }

    static int runArtifactJob(List<String> seeds, String artifactId, String className, List<String> args, int cpuCores, long memoryMb) {
        if (seeds.isEmpty()) {
            System.err.println("aegis run: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                try {
                    for (int i = 0; i < 60; i++) {
                        if (node.artifactRegistry().bySha256(artifactId).isPresent()) {
                            break;
                        }
                        Thread.sleep(50);
                    }
                    node.artifactRegistry().bySha256(artifactId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Unknown artifact: " + artifactId
                                            + ". Upload first with: aegis artifact upload <jar>"));

                    com.aegisos.api.JobHandle handle = node.api().getProcessManager()
                            .submitArtifact(artifactId, className, args, cpuCores, memoryMb);
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

    static int runAddVoter(List<String> seeds, String targetNodeIdHex) {
        if (seeds.isEmpty()) {
            System.err.println("aegis raft add-voter: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                try {
                    long start = System.currentTimeMillis();
                    while (node.consensus().leaderId() == null && (System.currentTimeMillis() - start) < 5000) {
                        Thread.sleep(100);
                    }
                    if (node.consensus().leaderId() == null) {
                        throw new IllegalStateException("Timeout waiting to discover cluster leader");
                    }
                    byte[] payload = com.aegisos.core.util.HexUtil.decode(targetNodeIdHex);
                    com.aegisos.proto.StateCommand cmd = com.aegisos.proto.StateCommand.newBuilder()
                            .setType(com.aegisos.proto.CommandType.ADD_VOTER)
                            .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                            .build();
                    node.consensus().propose(cmd).get(10, java.util.concurrent.TimeUnit.SECONDS);
                    System.out.println("Voter " + targetNodeIdHex + " added successfully.");
                    return 0;
                } catch (Exception e) {
                    System.err.println("Failed to add voter " + targetNodeIdHex + ": " + e.getMessage());
                    return 1;
                }
            });
        } catch (Exception e) {
            System.err.println("aegis raft add-voter failed: " + e.getMessage());
            return 1;
        }
    }

    static int runRemoveVoter(List<String> seeds, String targetNodeIdHex) {
        if (seeds.isEmpty()) {
            System.err.println("aegis raft remove-voter: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                try {
                    byte[] payload = com.aegisos.core.util.HexUtil.decode(targetNodeIdHex);
                    com.aegisos.proto.StateCommand cmd = com.aegisos.proto.StateCommand.newBuilder()
                            .setType(com.aegisos.proto.CommandType.REMOVE_VOTER)
                            .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                            .build();
                    node.consensus().propose(cmd).get(10, java.util.concurrent.TimeUnit.SECONDS);
                    System.out.println("Voter " + targetNodeIdHex + " removed successfully.");
                    return 0;
                } catch (Exception e) {
                    System.err.println("Failed to remove voter " + targetNodeIdHex + ": " + e.getMessage());
                    return 1;
                }
            });
        } catch (Exception e) {
            System.err.println("aegis raft remove-voter failed: " + e.getMessage());
            return 1;
        }
    }

    static int runJobsList(List<String> seeds) {
        if (seeds.isEmpty()) {
            System.err.println("aegis jobs list: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                try {
                    Thread.sleep(500); // allow raft catch-up
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                System.out.printf("%-36s %-12s %-14s %-6s %s%n", "JOB ID", "STATE", "NODE", "EXEC", "ERROR");
                for (com.aegisos.proto.JobRecord r : node.runtimeAgent().registry().all()) {
                    String nodeShort = r.getAssignedNodeId().isEmpty() ? "-"
                            : com.aegisos.core.identity.NodeId.of(r.getAssignedNodeId().toByteArray()).shortId();
                    String error = r.getError().isEmpty() ? "" : r.getError();
                    System.out.printf("%-36s %-12s %-14s %-6d %s%n",
                            r.getSpec().getJobId(), r.getState(), nodeShort, r.getExecutionId(), error);
                }
                return 0;
            });
        } catch (Exception e) {
            System.err.println("aegis jobs list failed: " + e.getMessage());
            return 1;
        }
    }

    static int runJobsCancel(List<String> seeds, String jobId) {
        if (seeds.isEmpty()) {
            System.err.println("aegis jobs cancel: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                try {
                    node.runtimeAgent().cancelJob(jobId);
                    System.out.println("Cancel requested for job " + jobId);
                    return 0;
                } catch (Exception e) {
                    System.err.println("cancel failed: " + e.getMessage());
                    return 1;
                }
            });
        } catch (Exception e) {
            System.err.println("aegis jobs cancel failed: " + e.getMessage());
            return 1;
        }
    }

    static int runJobsLogs(List<String> seeds, String jobId, Long executionId) {
        if (seeds.isEmpty()) {
            System.err.println("aegis jobs logs: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                try {
                    // Wait for job registry to replicate
                    for (int i = 0; i < 60 && node.runtimeAgent().registry().get(jobId).isEmpty(); i++) {
                        Thread.sleep(100);
                    }
                    com.aegisos.proto.JobRecord record = node.runtimeAgent().registry().get(jobId)
                            .orElseThrow(() -> new IllegalStateException("Unknown job: " + jobId));

                    long execId = executionId != null ? executionId : record.getExecutionId();
                    String stdoutPath = "/jobs/" + jobId + "/" + execId + "/stdout";
                    String stderrPath = "/jobs/" + jobId + "/" + execId + "/stderr";

                    System.out.println("=== STDOUT (execution " + execId + ") ===");
                    try {
                        byte[] stdout = node.fileSystem().read(stdoutPath);
                        System.out.println(new String(stdout, java.nio.charset.StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        System.out.println("(no stdout available)");
                    }

                    System.out.println("=== STDERR (execution " + execId + ") ===");
                    try {
                        byte[] stderr = node.fileSystem().read(stderrPath);
                        System.out.println(new String(stderr, java.nio.charset.StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        System.out.println("(no stderr available)");
                    }
                    return 0;
                } catch (Exception e) {
                    System.err.println("logs failed: " + e.getMessage());
                    return 1;
                }
            });
        } catch (Exception e) {
            System.err.println("aegis jobs logs failed: " + e.getMessage());
            return 1;
        }
    }



    static int runCluster(List<String> seeds) {
        if (seeds.isEmpty()) {
            System.err.println("aegis cluster: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                try {
                    Thread.sleep(500); // allow raft catch-up
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }

                String leaderIdHex = node.consensus().leaderId() != null 
                        ? com.aegisos.core.util.HexUtil.shortId(node.consensus().leaderId().toBytes()) 
                        : "NONE";
                long term = node.consensus().raftNode().currentTerm();

                System.out.println("Cluster");
                System.out.println("-------");
                System.out.println("Leader: " + leaderIdHex);
                System.out.println("Term: " + term);
                System.out.println("\nNodes");
                System.out.println("-----");

                java.util.Set<com.aegisos.core.identity.NodeId> voters = node.consensus().clusterConfiguration().voters();
                int aliveVoters = 0;

                for (com.aegisos.proto.PeerEntry p : node.discovery().membership().allPeers()) {
                    com.aegisos.core.identity.NodeId peerId = com.aegisos.core.identity.NodeId.of(p.getNodeId().toByteArray());
                    if (!voters.contains(peerId)) {
                        continue; // Only print cluster members (voters) for clarity, skip transient clients
                    }
                    String idStr = peerId.shortId();
                    String roleStr = peerId.equals(node.consensus().leaderId()) ? "LEADER" : "FOLLOWER";
                    
                    if (p.getStatus() == com.aegisos.proto.PeerStatus.ALIVE) {
                        aliveVoters++;
                    }
                    System.out.printf("%-10s %-10s %s%n", idStr, roleStr, p.getStatus());
                }

                System.out.println("\nQuorum: " + aliveVoters + "/" + voters.size());
                return 0;
            });
        } catch (Exception e) {
            System.err.println("aegis cluster failed: " + e.getMessage());
            return 1;
        }
    }

    static int runHealth(List<String> seeds) {
        if (seeds.isEmpty()) {
            System.err.println("aegis health: at least one --seed is required");
            return 2;
        }
        try {
            return withClient(seeds, node -> {
                com.aegisos.api.HealthSnapshot health = node.health();
                System.out.printf("%-11s %s%n", "Discovery", health.discoveryOk() ? "OK" : "DEGRADED");
                System.out.printf("%-11s %s%n", "Consensus", health.consensusOk() ? "OK" : "DEGRADED");
                System.out.printf("%-11s %s%n", "Scheduler", health.schedulerOk() ? "OK" : "DEGRADED");
                System.out.printf("%-11s %s%n", "Runtime", health.runtimeOk() ? "OK" : "DEGRADED");
                System.out.printf("%-11s %s%n", "Storage", health.storageOk() ? "OK" : "DEGRADED");
                System.out.println("\nOverall: " + (health.isHealthy() ? "HEALTHY" : "DEGRADED"));
                return 0;
            });
        } catch (Exception e) {
            System.err.println("aegis health failed: " + e.getMessage());
            return 1;
        }
    }

    private static int notReady(String cmd) {
        System.err.println("aegis " + cmd + ": not available in this build");
        return 2;
    }
}

package com.aegisos.node;

import com.aegisos.core.crypto.Hashing;
import com.aegisos.core.model.Endpoint;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime configuration for a node. Values come from CLI flags, a config file, or
 * sensible defaults.
 */
public final class NodeConfig {

    private Path homeDir = Path.of(System.getProperty("user.home"), ".aegis");
    private String bindHost = "0.0.0.0";
    private String advertiseHost = "127.0.0.1";
    private int port = 9000;
    private int apiPort = 9001;
    private final List<Endpoint> seeds = new ArrayList<>();
    private int replicationFactor = 3;
    private long reaperIntervalMs = 60_000;
    private long checkpointIntervalMs = 30_000;
    private com.aegisos.proto.NodeRole role = com.aegisos.proto.NodeRole.CLUSTER_MEMBER;
    // v0.1: a shared cluster secret wraps per-chunk keys. Override in production.
    private String clusterSecret = "aegis-dev-cluster-secret";

    public Path homeDir() {
        return homeDir;
    }

    public NodeConfig homeDir(Path dir) {
        this.homeDir = dir;
        return this;
    }

    public Path dataDir() {
        return homeDir.resolve("data");
    }

    public Path chunkDir() {
        return dataDir().resolve("chunks");
    }

    public Path raftDir() {
        return dataDir().resolve("raft");
    }

    public String bindHost() {
        return bindHost;
    }

    public NodeConfig bindHost(String h) {
        this.bindHost = h;
        return this;
    }

    public String advertiseHost() {
        return advertiseHost;
    }

    public NodeConfig advertiseHost(String h) {
        this.advertiseHost = h;
        return this;
    }

    public int port() {
        return port;
    }

    public NodeConfig port(int p) {
        this.port = p;
        return this;
    }

    public int apiPort() {
        return apiPort;
    }

    public NodeConfig apiPort(int p) {
        this.apiPort = p;
        return this;
    }

    public List<Endpoint> seeds() {
        return seeds;
    }

    public NodeConfig addSeed(Endpoint e) {
        seeds.add(e);
        return this;
    }

    public int replicationFactor() {
        return replicationFactor;
    }

    public NodeConfig replicationFactor(int rf) {
        this.replicationFactor = rf;
        return this;
    }

    public long reaperIntervalMs() {
        return reaperIntervalMs;
    }

    public NodeConfig reaperIntervalMs(long ms) {
        this.reaperIntervalMs = ms;
        return this;
    }

    public long checkpointIntervalMs() {
        return checkpointIntervalMs;
    }

    public NodeConfig checkpointIntervalMs(long ms) {
        this.checkpointIntervalMs = ms;
        return this;
    }

    public NodeConfig clusterSecret(String secret) {
        this.clusterSecret = secret;
        return this;
    }

    public com.aegisos.proto.NodeRole role() {
        return role;
    }

    public NodeConfig role(com.aegisos.proto.NodeRole r) {
        this.role = r;
        return this;
    }

    /** 32-byte AES key derived from the cluster secret; wraps per-chunk content keys. */
    public byte[] clusterKey() {
        return Hashing.sha256(clusterSecret.getBytes(StandardCharsets.UTF_8));
    }

    /** Loads seeds from {@code <homeDir>/seeds.conf} if present (one ip:port per line). */
    public NodeConfig loadSeedsFile() {
        Path seedsFile = homeDir.resolve("seeds.conf");
        if (!Files.exists(seedsFile)) {
            return this;
        }
        try {
            for (String line : Files.readAllLines(seedsFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                seeds.add(Endpoint.parse(trimmed));
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed reading " + seedsFile, e);
        }
        return this;
    }
}

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
    private int apiPort = 0;  // 0 = metrics server disabled
    private int restPort = 20001;
    private final List<Endpoint> seeds = new ArrayList<>();
    private int replicationFactor = 3;
    private long reaperIntervalMs = 60_000;
    private long checkpointIntervalMs = 30_000;
    private com.aegisos.proto.NodeRole role = com.aegisos.proto.NodeRole.CLUSTER_MEMBER;
    // v0.1: a shared cluster secret wraps per-chunk keys. Override in production.
    private String clusterSecret = "aegis-dev-cluster-secret";
    private boolean bootstrap = false;
    private int membershipLagThreshold = 10;
    private int repairRecommendationMaxAgeSeconds = 180;
    private int repairTaskTimeoutSeconds = 300;
    private int snapshotEntryThreshold = 1000;
    private long snapshotSizeThresholdBytes = 64 * 1024 * 1024; // 64 MB
    /** When false, the JobSupervisor (execution lease monitor) is not started.
     * Use this for storage-only test clusters that must not produce execution Raft entries. */
    private boolean jobSupervisorEnabled = true;
    /** When false, RepairProposer is not started, so divergences are audited but not automatically repaired. */
    private boolean repairEnabled = true;
    private long auditIntervalSeconds = Long.getLong("aegis.audit.interval.seconds", 60);
    
    // Sprint 9: Workspaces
    private int workspaceCleanupDelaySeconds = 300; // 5 minutes
    private long artifactCacheSizeMb = 1024; // 1GB default
    private int maxConcurrentReservations = 10; // Default limit

    public boolean bootstrap() {
        return bootstrap;
    }

    public NodeConfig bootstrap(boolean b) {
        this.bootstrap = b;
        return this;
    }

    public Path homeDir() {
        return homeDir;
    }

    public int membershipLagThreshold() {
        return membershipLagThreshold;
    }

    public NodeConfig membershipLagThreshold(int threshold) {
        this.membershipLagThreshold = threshold;
        return this;
    }

    public int repairRecommendationMaxAgeSeconds() {
        return repairRecommendationMaxAgeSeconds;
    }

    public NodeConfig repairRecommendationMaxAgeSeconds(int sec) {
        this.repairRecommendationMaxAgeSeconds = sec;
        return this;
    }

    public int repairTaskTimeoutSeconds() {
        return repairTaskTimeoutSeconds;
    }

    public NodeConfig repairTaskTimeoutSeconds(int sec) {
        this.repairTaskTimeoutSeconds = sec;
        return this;
    }

    public int snapshotEntryThreshold() {
        return snapshotEntryThreshold;
    }

    public NodeConfig snapshotEntryThreshold(int threshold) {
        this.snapshotEntryThreshold = threshold;
        return this;
    }

    public long snapshotSizeThresholdBytes() {
        return snapshotSizeThresholdBytes;
    }

    public NodeConfig snapshotSizeThresholdBytes(long bytes) {
        this.snapshotSizeThresholdBytes = bytes;
        return this;
    }

    public boolean jobSupervisorEnabled() {
        return jobSupervisorEnabled;
    }

    public NodeConfig jobSupervisorEnabled(boolean enabled) {
        this.jobSupervisorEnabled = enabled;
        return this;
    }

    public boolean repairEnabled() {
        return repairEnabled;
    }

    public NodeConfig repairEnabled(boolean enabled) {
        this.repairEnabled = enabled;
        return this;
    }

    public long auditIntervalSeconds() {
        return auditIntervalSeconds;
    }

    public NodeConfig auditIntervalSeconds(long seconds) {
        this.auditIntervalSeconds = seconds;
        return this;
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

    public Path artifactCacheDir() {
        return dataDir().resolve("artifacts");
    }

    public Path workspaceDir() {
        return dataDir().resolve("workspaces");
    }

    public int workspaceCleanupDelaySeconds() {
        return workspaceCleanupDelaySeconds;
    }

    public NodeConfig workspaceCleanupDelaySeconds(int sec) {
        this.workspaceCleanupDelaySeconds = sec;
        return this;
    }

    public long artifactCacheSizeMb() {
        return artifactCacheSizeMb;
    }

    public NodeConfig artifactCacheSizeMb(long mb) {
        this.artifactCacheSizeMb = mb;
        return this;
    }

    public int maxConcurrentReservations() {
        return maxConcurrentReservations;
    }

    public NodeConfig maxConcurrentReservations(int limit) {
        this.maxConcurrentReservations = limit;
        return this;
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

    public int restPort() {
        return restPort;
    }

    public NodeConfig restPort(int p) {
        this.restPort = p;
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

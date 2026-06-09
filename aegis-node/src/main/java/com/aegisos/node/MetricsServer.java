package com.aegisos.node;

import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.Executors;

/**
 * Minimal HTTP server exposing two endpoints on {@link NodeConfig#apiPort()}:
 *
 * <ul>
 *   <li>{@code GET /metrics} — rich JSON for humans and dashboards.</li>
 *   <li>{@code GET /health}  — minimal JSON for scripts and load-balancers.
 *       Returns HTTP 200 when the cluster is healthy, HTTP 503 otherwise.
 *       A cluster is considered healthy when this node knows who the leader is.</li>
 * </ul>
 *
 * <p>Health response shape:
 * <pre>
 * { "status": "UP", "leaderKnown": true, "alivePeers": 3 }   ← HTTP 200
 * { "status": "DOWN", "leaderKnown": false, "alivePeers": 1 } ← HTTP 503
 * </pre>
 *
 * <p>Uses the JDK built-in {@link HttpServer} — no extra dependencies.
 */
public final class MetricsServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetricsServer.class);

    private final AegisNode node;
    private final int port;
    private HttpServer server;

    public MetricsServer(AegisNode node, int port) {
        this.node = node;
        this.port = port;
    }

    public int boundPort() {
        return server != null ? server.getAddress().getPort() : port;
    }

    public void start() throws IOException {
        // Use explicit IPv4 wildcard + non-zero backlog; the JDK HttpServer on Windows
        // may silently fail to accept connections when bound with InetSocketAddress(port, 0).
        server = HttpServer.create(
                new InetSocketAddress("0.0.0.0", port), 10);

        server.createContext("/metrics", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = buildMetrics().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        server.createContext("/health", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            boolean leaderKnown = node.consensus().leaderId() != null;
            int alivePeers      = node.discovery().membership().aliveCount();
            // UP when a leader is known (quorum exists and cluster is making progress).
            // Scripts can rely on the HTTP status code alone — no JSON parsing needed.
            int httpStatus = leaderKnown ? 200 : 503;
            String body = String.format(
                    "{ \"status\": \"%s\", \"leaderKnown\": %b, \"alivePeers\": %d }%n",
                    leaderKnown ? "UP" : "DOWN", leaderKnown, alivePeers);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(httpStatus, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/cancel", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                String query = exchange.getRequestURI().getQuery();
                log.info("Received /cancel request with query: {}", query);
                if (query != null && query.startsWith("jobId=")) {
                    String jobId = query.substring(6);
                    if (jobId.isEmpty()) {
                        log.warn("Received empty jobId in /cancel request!");
                        exchange.sendResponseHeaders(400, -1);
                        return;
                    }
                    node.runtimeAgent().cancelJob(jobId);
                    String resp = "{\"status\":\"canceling\"}";
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, resp.getBytes().length);
                    java.io.OutputStream os = exchange.getResponseBody();
                    os.write(resp.getBytes());
                    os.close();
                    return;
                }
            }
            exchange.sendResponseHeaders(400, -1);
        });

        server.createContext("/membership", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = buildMembershipJson().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        server.createContext("/allocator", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String dump = node.resourceAllocator().dumpStatus();
            byte[] bytes = dump.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/audit/local-chunks", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            var chunks = node.fileSystem().chunkStore().listChunkIds();
            StringBuilder sb = new StringBuilder("[\n");
            for (int i = 0; i < chunks.size(); i++) {
                sb.append("  \"").append(chunks.get(i)).append("\"").append(i < chunks.size() - 1 ? ",\n" : "\n");
            }
            sb.append("]");
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.createContext("/audit/storage", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            try {
                com.aegisos.fs.audit.ChunkMetadataInventory inventory = new com.aegisos.fs.audit.ChunkMetadataInventory(node.fileSystem().fileIndex());
                java.util.List<com.aegisos.fs.audit.ChunkMetadataInventory.ChunkInventoryRecord> expected = 
                        inventory.build();
                        
                com.aegisos.fs.audit.ObservedStateCollector collector = new com.aegisos.fs.audit.ObservedStateCollector();
                java.util.Map<com.aegisos.core.identity.NodeId, java.util.Set<String>> observed = 
                        collector.observeRemoteState(node.network(), node.discovery().membership(), node.identity().nodeId(), node.fileSystem().chunkStore());
                        
                com.aegisos.fs.audit.DivergenceReportGenerator generator = new com.aegisos.fs.audit.DivergenceReportGenerator();
                java.util.List<com.aegisos.fs.audit.DivergenceReportGenerator.UnderReplicatedChunk> divergences = 
                        generator.detectUnderReplicated(expected, observed);

                StringBuilder sb = new StringBuilder();
                sb.append("{\n  \"underReplicatedChunks\": [\n");
                for (int i = 0; i < divergences.size(); i++) {
                    var chunk = divergences.get(i);
                    sb.append("    {\n");
                    sb.append("      \"chunkId\": \"").append(chunk.chunkIdHex).append("\",\n");
                    sb.append("      \"requiredReplicationFactor\": ").append(chunk.requiredReplicationFactor).append(",\n");
                    sb.append("      \"actualPhysicalCount\": ").append(chunk.actualPhysicalCount).append(",\n");
                    sb.append("      \"missingFromNodes\": [");
                    for (int j = 0; j < chunk.missingFromNodes.size(); j++) {
                        sb.append("\"").append(chunk.missingFromNodes.get(j).shortId()).append("\"");
                        if (j < chunk.missingFromNodes.size() - 1) sb.append(", ");
                    }
                    sb.append("]\n    }");
                    if (i < divergences.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("  ]\n}");
                
                byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (java.io.OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                log.error("Failed to generate storage audit report", e);
                exchange.sendResponseHeaders(500, -1);
            }
        });

        // A single virtual thread is sufficient — both endpoints are read-only and fast.

        server.createContext("/audit/verifications", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                var verifications = node.auditScheduler().getVerifications();
                StringBuilder sb = new StringBuilder("[\n");
                for (int i = 0; i < verifications.size(); i++) {
                    var v = verifications.get(i);
                    sb.append("  {\n");
                    sb.append("    \"chunkId\": \"").append(v.chunkId()).append("\",\n");
                    sb.append("    \"status\": \"").append(v.status().name()).append("\",\n");
                    sb.append("    \"details\": \"").append(escapeJson(v.details())).append("\",\n");
                    sb.append("    \"scanIdVerifiedAgainst\": ").append(v.scanIdVerifiedAgainst()).append(",\n");
                    sb.append("    \"evidenceScans\": ").append(v.evidenceScans()).append("\n");
                    sb.append("  }");
                    if (i < verifications.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("]");
                byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                log.error("Failed to generate verifications report", e);
                exchange.sendResponseHeaders(500, -1);
            }
        });

        server.createContext("/audit/recommendations", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                var recommendations = node.auditScheduler().getRecommendations();
                StringBuilder sb = new StringBuilder("[\n");
                for (int i = 0; i < recommendations.size(); i++) {
                    var r = recommendations.get(i);
                    sb.append("  {\n");
                    sb.append("    \"chunkId\": \"").append(r.chunkId()).append("\",\n");
                    sb.append("    \"divergenceType\": \"").append(r.divergenceType()).append("\",\n");
                    sb.append("    \"evidenceScans\": ").append(r.evidenceScans()).append(",\n");
                    sb.append("    \"recommendedAt\": ").append(r.recommendedAt()).append("\n");
                    sb.append("  }");
                    if (i < recommendations.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("]");
                byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                log.error("Failed to generate recommendations report", e);
                exchange.sendResponseHeaders(500, -1);
            }
        });

        server.createContext("/audit/repairs", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                var outcomes = node.auditScheduler().getHistoricalRepairOutcomes();
                StringBuilder sb = new StringBuilder("[\n");
                for (int i = 0; i < outcomes.size(); i++) {
                    var o = outcomes.get(i);
                    sb.append("  {\n");
                    sb.append("    \"chunkId\": \"").append(o.chunkId()).append("\",\n");
                    sb.append("    \"status\": \"").append(o.status().name()).append("\",\n");
                    sb.append("    \"details\": \"").append(escapeJson(o.details())).append("\",\n");
                    sb.append("    \"repairId\": ").append(o.repairId() == null ? "null" : "\"" + o.repairId() + "\"").append("\n");
                    sb.append("  }");
                    if (i < outcomes.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("]");
                byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                log.error("Failed to list repair outcomes", e);
                exchange.sendResponseHeaders(500, -1);
            }
        });

        server.createContext("/audit/tasks", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                var tasks = node.fileSystem().repairTaskStore().all();
                StringBuilder sb = new StringBuilder("[\n");
                for (int i = 0; i < tasks.size(); i++) {
                    var t = tasks.get(i);
                    sb.append("  {\n");
                    sb.append("    \"repairId\": \"").append(t.repairId()).append("\",\n");
                    sb.append("    \"chunkId\": \"").append(t.chunkIdHex()).append("\",\n");
                    sb.append("    \"evidenceScans\": ").append(t.evidenceScans()).append(",\n");
                    sb.append("    \"verifiedAt\": ").append(t.verifiedAt()).append(",\n");
                    sb.append("    \"committedAt\": ").append(t.committedAt()).append(",\n");
                    sb.append("    \"status\": \"").append(t.status().name()).append("\"\n");
                    sb.append("  }");
                    if (i < tasks.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append("]");
                byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                log.error("Failed to list repair tasks", e);
                exchange.sendResponseHeaders(500, -1);
            }
        });

        server.createContext("/raft/metrics", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                var raftNode = node.consensus().raftNode();
                var raftLog = raftNode.raftLog();
                String body = String.format("""
                        {
                          "logEntryCount"        : %d,
                          "logSizeEstimateBytes" : %d,
                          "diskSizeBytes"        : %d,
                          "commitIndex"          : %d,
                          "lastApplied"          : %d,
                          "lastLogIndex"         : %d,
                          "currentTerm"          : %d
                        }
                        """,
                        raftLog.entryCount(),
                        raftLog.logSizeEstimateBytes(),
                        raftLog.diskSizeBytes(),
                        raftNode.commitIndex(),
                        raftNode.lastApplied(),
                        raftNode.lastLogIndex(),
                        raftNode.currentTerm());
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                log.error("Failed to generate Raft metrics", e);
                exchange.sendResponseHeaders(500, -1);
            }
        });

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log.info("Metrics server listening on http://0.0.0.0:{}/ (endpoints: /metrics, /health)", port);
    }

    private String buildMembershipJson() {
        com.aegisos.consensus.ClusterConfiguration config = node.consensus().clusterConfiguration();
        long version = config.version();
        java.util.Set<com.aegisos.core.identity.NodeId> voters = config.voters();
        
        java.util.List<com.aegisos.proto.PeerEntry> gossipPeersList = node.discovery().membership().allPeers();
        
        java.util.Set<String> raftSet = new java.util.HashSet<>();
        for (com.aegisos.core.identity.NodeId id : voters) {
            raftSet.add(id.shortId());
        }
        
        java.util.Set<String> gossipSet = new java.util.HashSet<>();
        gossipSet.add(node.identity().nodeId().shortId());
        for (com.aegisos.proto.PeerEntry p : gossipPeersList) {
            gossipSet.add(com.aegisos.core.util.HexUtil.shortId(p.getNodeId().toByteArray()));
        }
        
        java.util.List<String> votersNotInGossip = new java.util.ArrayList<>();
        for (String r : raftSet) {
            if (!gossipSet.contains(r)) votersNotInGossip.add(r);
        }
        
        java.util.List<String> gossipNotInVoters = new java.util.ArrayList<>();
        for (String g : gossipSet) {
            if (!raftSet.contains(g)) gossipNotInVoters.add(g);
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"raftConfiguration\": {\n");
        sb.append("    \"version\": ").append(version).append(",\n");
        sb.append("    \"voters\": [");
        int count = 0;
        for (com.aegisos.core.identity.NodeId id : voters) {
            sb.append("\"").append(id.shortId()).append("\"");
            if (++count < voters.size()) sb.append(", ");
        }
        sb.append("]\n  },\n");
        
        sb.append("  \"gossipPeers\": [\n");
        if (!gossipPeersList.isEmpty()) {
            count = 0;
            for (com.aegisos.proto.PeerEntry p : gossipPeersList) {
                String id = com.aegisos.core.util.HexUtil.shortId(p.getNodeId().toByteArray());
                sb.append("    { \"nodeId\": \"").append(id)
                  .append("\", \"status\": \"").append(p.getStatus().name())
                  .append("\", \"role\": \"").append(p.getRole().name()).append("\" }");
                if (++count < gossipPeersList.size()) sb.append(",\n");
            }
        }
        sb.append("\n  ],\n");
        
        sb.append("  \"delta\": {\n");
        sb.append("    \"votersNotInGossip\": [");
        count = 0;
        for (String id : votersNotInGossip) {
            sb.append("\"").append(id).append("\"");
            if (++count < votersNotInGossip.size()) sb.append(", ");
        }
        sb.append("],\n");
        
        sb.append("    \"gossipNotInVoters\": [");
        count = 0;
        for (String id : gossipNotInVoters) {
            sb.append("\"").append(id).append("\"");
            if (++count < gossipNotInVoters.size()) sb.append(", ");
        }
        sb.append("]\n  }\n}\n");
        
        return sb.toString();
    }

    private String buildMetrics() {
        String nodeId = node.identity().nodeId().shortId();

        // --- Consensus ---
        boolean isLeader = node.consensus().isLeader();
        String leaderId = null;
        if (node.consensus().leaderId() != null) {
            leaderId = node.consensus().leaderId().shortId();
        }
        long term        = node.consensus().raftNode().currentTerm();
        long commitIndex = node.consensus().raftNode().commitIndex();
        String role = isLeader ? "LEADER"
                : (leaderId != null ? "FOLLOWER" : "CANDIDATE");

        // --- Membership ---
        int aliveNodes = node.discovery().membership().aliveCount();

        // --- Jobs ---
        Collection<JobRecord> allJobs = node.runtimeAgent().registry().all();
        long queued    = allJobs.stream().filter(j -> j.getState() == JobState.QUEUED).count();
        long pending   = allJobs.stream().filter(j -> j.getState() == JobState.PENDING).count();
        long running   = allJobs.stream().filter(j -> j.getState() == JobState.RUNNING).count();
        long completed = allJobs.stream().filter(j -> j.getState() == JobState.COMPLETED).count();
        long failed    = allJobs.stream().filter(j -> j.getState() == JobState.FAILED).count();

        // --- Storage ---
        int localChunks = node.fileSystem().chunkStore().listChunkIds().size();

        // Hand-built JSON — no Jackson dependency needed.
        return String.format("""
                {
                  "nodeId"      : "%s",
                  "role"        : "%s",
                  "leader"      : %s,
                  "term"        : %d,
                  "commitIndex" : %d,
                  "aliveNodes"  : %d,
                  "jobs"        : { "PENDING": %d, "QUEUED": %d, "RUNNING": %d, "COMPLETED": %d, "FAILED": %d },
                  "localChunks" : %d
                }
                """,
                nodeId,
                role,
                leaderId == null ? "null" : "\"" + leaderId + "\"",
                term,
                commitIndex,
                aliveNodes,
                pending, queued, running, completed, failed,
                localChunks);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(1); // Use 1-second timeout to prevent shutdown hanging
            log.info("Metrics server stopped");
        }
    }


}

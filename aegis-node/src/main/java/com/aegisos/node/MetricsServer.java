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

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

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

        // A single virtual thread is sufficient — both endpoints are read-only and fast.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log.info("Metrics server listening on http://0.0.0.0:{}/ (endpoints: /metrics, /health)", port);
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
                  "jobs"        : { "QUEUED": %d, "RUNNING": %d, "COMPLETED": %d, "FAILED": %d },
                  "localChunks" : %d
                }
                """,
                nodeId,
                role,
                leaderId == null ? "null" : "\"" + leaderId + "\"",
                term,
                commitIndex,
                aliveNodes,
                queued, running, completed, failed,
                localChunks);
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            log.info("Metrics server stopped");
        }
    }
}

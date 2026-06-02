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
import java.util.stream.Collectors;

/**
 * Minimal HTTP metrics server exposed on {@link NodeConfig#apiPort()}.
 *
 * <p>Serves a single JSON endpoint at {@code GET /metrics} with cluster-health
 * indicators useful for dashboards, integration tests, and demos:
 *
 * <pre>
 * {
 *   "nodeId"      : "a3f5b2...",
 *   "role"        : "LEADER" | "FOLLOWER" | "CANDIDATE",
 *   "leader"      : "a3f5b2..." | null,
 *   "term"        : 12,
 *   "commitIndex" : 47,
 *   "aliveNodes"  : 3,
 *   "jobs"        : { "QUEUED": 0, "RUNNING": 2, "COMPLETED": 15, "FAILED": 1 },
 *   "localChunks" : 38
 * }
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
        // A single virtual thread is sufficient — metrics are read-only and fast.
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log.info("Metrics server listening on http://0.0.0.0:{}/metrics", port);
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

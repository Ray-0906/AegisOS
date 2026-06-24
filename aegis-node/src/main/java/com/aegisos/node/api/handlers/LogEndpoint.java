package com.aegisos.node.api.handlers;

import com.aegisos.api.ClusterInfo;
import com.aegisos.api.NodeInfo;
import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.api.runtime.ProcessTable;
import com.aegisos.core.identity.IdentityService;
import com.aegisos.node.api.ResponseWriter;
import com.sun.net.httpserver.HttpExchange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class LogEndpoint {

    private static final Logger log = LoggerFactory.getLogger(LogEndpoint.class);
    private final ProcessTable processTable;
    private final IdentityService identity;
    private final ClusterInfo clusterInfo;
    private final Path logDir;

    public LogEndpoint(ProcessTable processTable, IdentityService identity, ClusterInfo clusterInfo, Path logDir) {
        this.processTable = processTable;
        this.identity = identity;
        this.clusterInfo = clusterInfo;
        this.logDir = logDir;
    }

    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        if (!"GET".equals(method)) {
            ResponseWriter.writeError(exchange, 405, "METHOD_NOT_ALLOWED");
            return;
        }

        String[] parts = path.split("/");
        if (parts.length < 5) {
            ResponseWriter.writeError(exchange, 400, "BAD_REQUEST");
            return;
        }

        String processId = parts[3];

        Optional<ProcessRecord> optionalProcess = processTable.lookup(processId);
        if (optionalProcess.isEmpty()) {
            ResponseWriter.writeError(exchange, 404, "NOT_FOUND");
            return;
        }

        ProcessRecord process = optionalProcess.get();
        String localNodeIdHex = identity.nodeId().toHex();

        if (!localNodeIdHex.equals(process.ownerNodeId())) {
            Optional<NodeInfo> ownerNode = clusterInfo.getAliveNodes().stream()
                    .filter(n -> n.nodeId().equals(process.ownerNodeId()))
                    .findFirst();

            if (ownerNode.isPresent()) {
                String gossipAddress = ownerNode.get().address();
                String ownerIp = gossipAddress.split(":")[0];
                
                // TODO: Phase 6 - Broadcast REST API port via Gossip to support multi-node localhost clusters.
                int ownerPort = 18000;
                log.warn("Log streaming redirecting to default port 18000. Multi-node localhost clusters will fail.");

                String redirectUrl = "http://" + ownerIp + ":" + ownerPort + "/v1/processes/" + processId + "/logs";
                exchange.getResponseHeaders().set("Location", redirectUrl);
                exchange.sendResponseHeaders(307, -1);
            } else {
                ResponseWriter.writeError(exchange, 503, "OWNER_NODE_UNAVAILABLE");
            }
            return;
        }

        Path logFile = logDir.resolve(processId + ".log");
        if (!Files.exists(logFile)) {
            ResponseWriter.writeError(exchange, 404, "LOG_FILE_NOT_FOUND");
            return;
        }

        exchange.getResponseHeaders().set("Transfer-Encoding", "chunked");
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream os = exchange.getResponseBody();
             InputStream is = Files.newInputStream(logFile)) {
            byte[] buffer = new byte[8192];
            while (true) {
                int read = is.read(buffer);
                if (read > 0) {
                    os.write(buffer, 0, read);
                    os.flush();
                } else {
                    ProcessRecord currentProcess = processTable.lookup(processId).orElse(null);
                    if (currentProcess != null && currentProcess.state() == ProcessState.RUNNING) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    } else {
                        break;
                    }
                }
            }
        }
    }
}

package com.aegisos.node.api.handlers;

import com.aegisos.api.dto.file.UploadFileResponse;
import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.node.api.ResponseWriter;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.stream.Collectors;
import com.aegisos.api.dto.file.ListFilesResponse;

public class FileHandler {

    private static final Logger log = LoggerFactory.getLogger(FileHandler.class);
    private final AegisNode node;

    public FileHandler(AegisNode node) {
        this.node = node;
    }

    private boolean checkLeader(HttpExchange exchange) throws IOException {
        if (!node.consensus().isLeader()) {
            NodeId leaderId = node.consensus().leaderId();
            if (leaderId == null) {
                ResponseWriter.writeError(exchange, 503, "NOT_LEADER");
            } else {
                int apiPort = node.discovery().membership().restPortOf(leaderId);
                if (apiPort == 0) {
                    exchange.sendResponseHeaders(503, -1); // 503 Service Unavailable (Election in progress)
                    return false;
                }
                ResponseWriter.writeError(exchange, 503, "NOT_LEADER", leaderId.shortId(), apiPort);
            }
            return false;
        }
        return true;
    }

    public void putFile(HttpExchange exchange, String path) throws IOException {
        if (!checkLeader(exchange)) return;

        try {
            // Read body into memory
            // For v1.3, we assume file fits in memory. 
            // In the future we will use streaming for larger files.
            InputStream is = exchange.getRequestBody();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            byte[] data = baos.toByteArray();

            // Prefix the path with slash if missing
            String remotePath = path.startsWith("/") ? path : "/" + path;

            node.fileSystem().write(remotePath, data);

            // TODO: chunkCount calculation can be queried from fileIndex if needed
            // For now, we return 1 as a placeholder or we can omit it
            int chunkCount = (int) Math.ceil((double) data.length / (2 * 1024 * 1024)); // Assuming 2MB chunks

            UploadFileResponse resp = new UploadFileResponse(remotePath, "UPLOADED", data.length, Math.max(1, chunkCount));
            ResponseWriter.writeJson(exchange, 201, resp);
        } catch (Exception e) {
            log.error("Failed to upload file {}", path, e);
            ResponseWriter.writeError(exchange, 503, "SERVICE_UNAVAILABLE");
        }
    }

    public void getFile(HttpExchange exchange, String path) throws IOException {
        try {
            String remotePath = path.startsWith("/") ? path : "/" + path;

            // Wait briefly for file metadata to replicate if not yet visible (borrowed from CLI logic)
            for (int i = 0; i < 20 && node.fileSystem().fileIndex().byName(remotePath).isEmpty(); i++) {
                Thread.sleep(50);
            }

            if (node.fileSystem().fileIndex().byName(remotePath).isEmpty()) {
                ResponseWriter.writeError(exchange, 404, "RESOURCE_NOT_FOUND");
                return;
            }

            byte[] data = node.fileSystem().read(remotePath);

            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        } catch (Exception e) {
            log.error("Failed to download file {}", path, e);
            ResponseWriter.writeError(exchange, 503, "SERVICE_UNAVAILABLE");
        }
    }

    public void listFiles(HttpExchange exchange, String path) throws IOException {
        try {
            // Wait briefly for file metadata to replicate if not yet visible
            Thread.sleep(500); // allow raft catch-up, as in CLI

            java.util.List<com.aegisos.proto.FileMetadata> protoFiles = node.fileSystem().list(path);
            java.util.List<ListFilesResponse.FileInfo> files = protoFiles.stream()
                    .map(f -> new ListFilesResponse.FileInfo(f.getName(), f.getSize(), f.getChunksCount()))
                    .collect(Collectors.toList());

            ResponseWriter.writeJson(exchange, 200, new ListFilesResponse(files));
        } catch (Exception e) {
            log.error("Failed to list files at {}", path, e);
            ResponseWriter.writeError(exchange, 503, "SERVICE_UNAVAILABLE");
        }
    }
}

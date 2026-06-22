package com.aegisos.node.api.handlers;

import com.aegisos.api.dto.artifact.ArtifactSummary;
import com.aegisos.api.dto.artifact.ArtifactUploadResponse;
import com.aegisos.core.crypto.Hashing;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.util.HexUtil;
import com.aegisos.node.AegisNode;
import com.aegisos.node.api.ResponseWriter;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

public class ArtifactHandler {
    private static final Logger log = LoggerFactory.getLogger(ArtifactHandler.class);
    private final AegisNode node;

    public ArtifactHandler(AegisNode node) {
        this.node = node;
    }

    private boolean checkLeader(HttpExchange exchange) throws IOException {
        if (!node.consensus().isLeader()) {
            NodeId leaderId = node.consensus().leaderId();
            if (leaderId == null) {
                ResponseWriter.writeError(exchange, 503, "NOT_LEADER");
            } else {
                int apiPort = 20001; // Future: get from Gossip
                ResponseWriter.writeError(exchange, 503, "NOT_LEADER", leaderId.shortId(), apiPort);
            }
            return false;
        }
        return true;
    }

    public void uploadArtifact(HttpExchange exchange, String name) throws IOException {
        if (!checkLeader(exchange)) return;

        try {
            InputStream is = exchange.getRequestBody();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            byte[] data = baos.toByteArray();

            String sha256 = HexUtil.encode(Hashing.sha256(data));

            // INV-060: Deduplication
            if (node.artifactRegistry().bySha256(sha256).isPresent()) {
                ResponseWriter.writeJson(exchange, 200, new ArtifactUploadResponse(sha256, name, data.length));
                return;
            }

            // INV-059: Artifact bytes -> ClusterFS
            String fsPath = "/artifacts/" + sha256;
            node.fileSystem().write(fsPath, data);

            // INV-059: Artifact metadata -> ArtifactRegistry (via Raft)
            com.aegisos.proto.ArtifactRecord record = com.aegisos.proto.ArtifactRecord.newBuilder()
                    .setArtifactId(sha256)
                    .setFileName(name)
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

            ResponseWriter.writeJson(exchange, 201, new ArtifactUploadResponse(sha256, name, data.length));
        } catch (Exception e) {
            log.error("Failed to upload artifact {}", name, e);
            ResponseWriter.writeError(exchange, 503, "SERVICE_UNAVAILABLE");
        }
    }

    public void listArtifacts(HttpExchange exchange) throws IOException {
        try {
            // Wait briefly for metadata to replicate if not yet visible
            Thread.sleep(500);

            List<ArtifactSummary> artifacts = node.artifactRegistry().listAll().stream()
                    .map(r -> new ArtifactSummary(
                            r.getArtifactId(),
                            r.getFileName(),
                            r.getSize(),
                            r.getCreatedAt()))
                    .collect(Collectors.toList());

            ResponseWriter.writeJson(exchange, 200, artifacts);
        } catch (Exception e) {
            log.error("Failed to list artifacts", e);
            ResponseWriter.writeError(exchange, 503, "SERVICE_UNAVAILABLE");
        }
    }
}

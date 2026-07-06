package com.aegisos.node.api.handlers;

import com.aegisos.api.dto.job.JobDetails;
import com.aegisos.api.dto.job.JobRequest;
import com.aegisos.api.dto.job.JobResources;
import com.aegisos.api.dto.job.JobSummary;
import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.node.api.ResponseWriter;
import com.aegisos.proto.JobRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class JobHandler {

    private static final Logger log = LoggerFactory.getLogger(JobHandler.class);
    private final AegisNode node;
    private final ObjectMapper mapper = new ObjectMapper();

    public JobHandler(AegisNode node) {
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

    public void submitJob(HttpExchange exchange) throws IOException {
        if (!checkLeader(exchange)) return;

        try {
            JobRequest req = mapper.readValue(exchange.getRequestBody(), JobRequest.class);
            String targetArtifact = req.artifact();
            if (targetArtifact == null || targetArtifact.trim().isEmpty()) {
                targetArtifact = req.entrypoint();
            }
            com.aegisos.api.JobHandle handle = node.api().getProcessManager().submitArtifact(
                    targetArtifact,
                    req.entrypoint(),
                    req.args(),
                    req.resources() != null ? req.resources().cpu() : 1,
                    req.resources() != null ? req.resources().memoryMb() : 1024
            );

            // Return just the job ID
            ResponseWriter.writeJson(exchange, 202, handle.jobId());
        } catch (Exception e) {
            log.error("Failed to submit job", e);
            ResponseWriter.writeError(exchange, 503, "SERVICE_UNAVAILABLE");
        }
    }

    public void listJobs(HttpExchange exchange) throws IOException {
        try {
            List<JobSummary> summaries = node.runtimeAgent().registry().all().stream()
                    .map(r -> new JobSummary(
                            r.getSpec().getJobId(),
                            r.getState().name(),
                            r.getAssignedNodeId().isEmpty() ? "-" : NodeId.of(r.getAssignedNodeId().toByteArray()).shortId(),
                            r.getExecutionId(),
                            r.getError()
                    ))
                    .collect(Collectors.toList());

            ResponseWriter.writeJson(exchange, 200, summaries);
        } catch (Exception e) {
            log.error("Failed to list jobs", e);
            ResponseWriter.writeError(exchange, 503, "SERVICE_UNAVAILABLE");
        }
    }

    public void getJob(HttpExchange exchange, String jobId) throws IOException {
        try {
            Optional<JobRecord> optRecord = node.runtimeAgent().registry().get(jobId);
            if (optRecord.isEmpty()) {
                ResponseWriter.writeError(exchange, 404, "RESOURCE_NOT_FOUND");
                return;
            }

            JobRecord r = optRecord.get();
            JobDetails details = new JobDetails(
                    r.getSpec().getJobId(),
                    r.getState().name(),
                    r.getAssignedNodeId().isEmpty() ? "-" : NodeId.of(r.getAssignedNodeId().toByteArray()).shortId(),
                    r.getExecutionId(),
                    r.getError(),
                    new JobResources(r.getSpec().getResources().getCpuCores(), r.getSpec().getResources().getMemoryMb())
            );

            ResponseWriter.writeJson(exchange, 200, details);
        } catch (Exception e) {
            log.error("Failed to get job", e);
            ResponseWriter.writeError(exchange, 503, "SERVICE_UNAVAILABLE");
        }
    }

    public void cancelJob(HttpExchange exchange, String jobId) throws IOException {
        if (!checkLeader(exchange)) return;
        try {
            node.runtimeAgent().cancelJob(jobId);
            exchange.sendResponseHeaders(202, -1);
        } catch (Exception e) {
            log.error("Failed to cancel job", e);
            ResponseWriter.writeError(exchange, 503, "SERVICE_UNAVAILABLE");
        }
    }

    public void getJobLogs(HttpExchange exchange, String jobId, String streamType, Long executionId) throws IOException {
        try {
            Optional<JobRecord> optRecord = node.runtimeAgent().registry().get(jobId);
            if (optRecord.isEmpty()) {
                ResponseWriter.writeError(exchange, 404, "RESOURCE_NOT_FOUND");
                return;
            }

            JobRecord record = optRecord.get();
            long execId = executionId != null ? executionId : record.getExecutionId();

            // --- Reverse proxy: forward to the assigned node if it's not us ---
            if (!record.getAssignedNodeId().isEmpty()) {
                NodeId assignedNode = NodeId.of(record.getAssignedNodeId().toByteArray());
                NodeId self = node.identity().nodeId();
                if (!assignedNode.equals(self)) {
                    int remoteApiPort = node.discovery().membership().restPortOf(assignedNode);
                    Optional<com.aegisos.core.model.Endpoint> endpointOpt = node.discovery().membership().endpointOf(assignedNode);
                    if (remoteApiPort > 0 && endpointOpt.isPresent()) {
                        String remoteHost = endpointOpt.get().host();
                        String queryString = "stream=" + streamType + "&executionId=" + execId;
                        String url = "http://" + remoteHost + ":" + remoteApiPort
                                + "/v1/processes/" + jobId + "/logs?" + queryString;
                        try {
                            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                                    .connectTimeout(java.time.Duration.ofSeconds(5))
                                    .build();
                            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                                    .uri(java.net.URI.create(url))
                                    .timeout(java.time.Duration.ofSeconds(10))
                                    .GET()
                                    .build();
                            java.net.http.HttpResponse<byte[]> resp = client.send(req,
                                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                            exchange.getResponseHeaders().set("Content-Type",
                                    resp.headers().firstValue("Content-Type").orElse("text/plain"));
                            exchange.sendResponseHeaders(resp.statusCode(), resp.body().length);
                            try (OutputStream os = exchange.getResponseBody()) {
                                os.write(resp.body());
                            }
                            return;
                        } catch (Exception proxyEx) {
                            log.warn("Failed to proxy log request for job {} to {}: {}",
                                    jobId, assignedNode.shortId(), proxyEx.getMessage());
                            // Fall through to local read as a best-effort fallback
                        }
                    }
                }
            }

            // --- Local read: AegisFS first, then local workspace fallback ---
            String path = "/jobs/" + jobId + "/" + execId + "/" + streamType;

            byte[] data;
            try {
                data = node.fileSystem().read(path);
            } catch (Exception e) {
                // File does not exist or cannot be read yet (might still be running locally)
                try {
                    Path localLogPath = node.config().workspaceDir().resolve(jobId).resolve("exec-" + execId).resolve(streamType + ".log");
                    if (Files.exists(localLogPath)) {
                        data = Files.readAllBytes(localLogPath);
                    } else {
                        ResponseWriter.writeError(exchange, 404, "RESOURCE_NOT_FOUND");
                        return;
                    }
                } catch (Exception ex) {
                    ResponseWriter.writeError(exchange, 404, "RESOURCE_NOT_FOUND");
                    return;
                }
            }

            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        } catch (Exception e) {
            log.error("Failed to get job logs", e);
            ResponseWriter.writeError(exchange, 503, "SERVICE_UNAVAILABLE");
        }
    }
}

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
            com.aegisos.api.JobHandle handle;
            
            if (req.artifact() != null && !req.artifact().isEmpty()) {
                handle = node.api().getProcessManager().submitArtifact(
                        req.artifact(),
                        req.entrypoint(),
                        req.args(),
                        req.resources() != null ? req.resources().cpu() : 1,
                        req.resources() != null ? req.resources().memoryMb() : 1024
                );
            } else {
                // Instantiate the job on the server side
                Class<?> clazz = Class.forName(req.entrypoint());
                Object instance;
                try {
                    instance = clazz.getConstructor(String[].class)
                            .newInstance((Object) req.args().toArray(new String[0]));
                } catch (NoSuchMethodException e) {
                    instance = clazz.getDeclaredConstructor().newInstance();
                }
                if (!(instance instanceof com.aegisos.runtime.AegisJob<?> job)) {
                    throw new IllegalArgumentException(req.entrypoint() + " is not an AegisJob");
                }
                handle = node.api().getProcessManager().submit(
                        job,
                        req.resources() != null ? req.resources().cpu() : 1,
                        req.resources() != null ? req.resources().memoryMb() : 1024
                );
            }

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

            long execId = executionId != null ? executionId : optRecord.get().getExecutionId();
            String path = "/jobs/" + jobId + "/" + execId + "/" + streamType;

            byte[] data;
            try {
                data = node.fileSystem().read(path);
            } catch (Exception e) {
                // File does not exist or cannot be read yet
                ResponseWriter.writeError(exchange, 404, "RESOURCE_NOT_FOUND");
                return;
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

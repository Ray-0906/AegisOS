package com.aegisos.client;

import com.aegisos.api.dto.cluster.HealthResponse;
import com.aegisos.api.dto.cluster.LeaderResponse;
import com.aegisos.api.dto.cluster.NodeResponse;
import com.aegisos.api.dto.file.UploadFileResponse;
import com.aegisos.api.dto.job.JobDetails;
import com.aegisos.api.dto.job.JobRequest;
import com.aegisos.api.dto.job.JobSummary;
import com.aegisos.api.dto.membership.MembershipRequest;
import com.aegisos.api.dto.membership.MembershipResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

public class AegisClient {
    private static final Logger log = LoggerFactory.getLogger(AegisClient.class);

    private final RestTransport transport;
    private final LeaderResolver leaderResolver;

    public AegisClient(List<URI> seeds) {
        this.transport = new RestTransport();
        this.leaderResolver = new LeaderResolver(transport, seeds);
    }

    public UploadFileResponse putFile(String path, byte[] data) {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/files/" + trimLeadingSlash(path));
            return transport.putBinary(target, data, UploadFileResponse.class);
        });
    }

    public byte[] getFile(String path) {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/files/" + trimLeadingSlash(path));
            return transport.getBinary(target);
        });
    }

    public com.aegisos.api.dto.file.ListFilesResponse listFiles(String path) {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            // In v1.3 we only support listing /, but we can pass path as query param if needed
            // For now, let's just hit /v1/files or /v1/files/
            String targetPath = path.equals("/") ? "/v1/files" : "/v1/files/" + trimLeadingSlash(path);
            URI target = leader.resolve(targetPath);
            return transport.get(target, com.aegisos.api.dto.file.ListFilesResponse.class);
        });
    }

    public List<NodeResponse> getNodes() {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/nodes");
            NodeResponse[] nodes = transport.get(target, NodeResponse[].class);
            return Arrays.asList(nodes);
        });
    }

    public LeaderResponse getLeader() {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/leader");
            return transport.get(target, LeaderResponse.class);
        });
    }

    public HealthResponse getHealth() {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/health");
            return transport.get(target, HealthResponse.class);
        });
    }

    public MembershipResponse addMember(String nodeId) {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/admin/membership");
            return transport.post(target, new MembershipRequest(nodeId), MembershipResponse.class);
        });
    }

    public MembershipResponse removeMember(String nodeId) {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/admin/membership/" + nodeId);
            return transport.delete(target, MembershipResponse.class);
        });
    }

    public String submitJob(JobRequest req) {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/jobs");
            return transport.post(target, req, String.class);
        });
    }

    public List<JobSummary> listJobs() {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/jobs");
            JobSummary[] summaries = transport.get(target, JobSummary[].class);
            return Arrays.asList(summaries);
        });
    }

    public JobDetails getJobStatus(String jobId) {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/jobs/" + jobId);
            return transport.get(target, JobDetails.class);
        });
    }

    public void cancelJob(String jobId) {
        executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/jobs/" + jobId);
            transport.delete(target, Void.class);
            return null;
        });
    }

    public String getJobLogs(String jobId, String stream, Long executionId) {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            String path = "/v1/jobs/" + jobId + "/logs?stream=" + stream;
            if (executionId != null) {
                path += "&executionId=" + executionId;
            }
            URI target = leader.resolve(path);
            byte[] data = transport.getBinary(target);
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        });
    }

    public com.aegisos.api.dto.artifact.ArtifactUploadResponse uploadArtifact(String name, byte[] data) {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/artifacts?name=" + name);
            return transport.postBinary(target, data, com.aegisos.api.dto.artifact.ArtifactUploadResponse.class);
        });
    }

    public List<com.aegisos.api.dto.artifact.ArtifactSummary> listArtifacts() {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/artifacts");
            com.aegisos.api.dto.artifact.ArtifactSummary[] summaries = transport.get(target, com.aegisos.api.dto.artifact.ArtifactSummary[].class);
            return Arrays.asList(summaries);
        });
    }

    public String submitProcess(String artifactId, int cpuCores, long memoryMb) {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/processes");
            com.aegisos.api.runtime.ProcessResources resources = new com.aegisos.api.runtime.ProcessResources(cpuCores, memoryMb);
            java.util.Map<String, Object> req = java.util.Map.of("artifactId", artifactId, "resources", resources);
            java.util.Map response = transport.post(target, req, java.util.Map.class);
            return (String) response.get("processId");
        });
    }

    public com.aegisos.api.runtime.ProcessRecord getProcess(String processId) {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/processes/" + processId);
            return transport.get(target, com.aegisos.api.runtime.ProcessRecord.class);
        });
    }

    public List<com.aegisos.api.runtime.ProcessRecord> listProcesses() {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/processes");
            com.aegisos.api.runtime.ProcessRecord[] processes = transport.get(target, com.aegisos.api.runtime.ProcessRecord[].class);
            return Arrays.asList(processes);
        });
    }

    public void cancelProcess(String processId) {
        executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/processes/" + processId);
            transport.delete(target, Void.class);
            return null;
        });
    }

    private <T> T executeWithRetry(ClientOperation<T> operation) {
        try {
            return operation.execute();
        } catch (NotLeaderException e) {
            log.info("Node is not leader. Invalidating cache and retrying once. New leader: {} at port {}", e.getLeaderId(), e.getApiPort());
            leaderResolver.invalidateLeader();
            // Force discovery immediately to use the newly discovered leader
            leaderResolver.discoverLeader();
            try {
                return operation.execute();
            } catch (Exception retryEx) {
                throw new RuntimeException("Operation failed after retry", retryEx);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Operation failed", e);
        }
    }

    private String trimLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    @FunctionalInterface
    private interface ClientOperation<T> {
        T execute() throws Exception;
    }
}

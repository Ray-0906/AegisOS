package com.aegisos.client;

import com.aegisos.api.dto.cluster.NodeResponse;
import com.aegisos.api.dto.file.UploadFileResponse;
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

    public List<NodeResponse> getNodes() {
        return executeWithRetry(() -> {
            URI leader = leaderResolver.getLeader();
            URI target = leader.resolve("/v1/nodes");
            NodeResponse[] nodes = transport.get(target, NodeResponse[].class);
            return Arrays.asList(nodes);
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

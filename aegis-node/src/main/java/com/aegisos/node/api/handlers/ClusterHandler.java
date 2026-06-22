package com.aegisos.node.api.handlers;

import com.aegisos.api.dto.cluster.HealthResponse;
import com.aegisos.api.dto.cluster.LeaderResponse;
import com.aegisos.api.dto.cluster.NodeResponse;
import com.aegisos.core.identity.NodeId;
import com.aegisos.proto.PeerEntry;
import com.aegisos.node.AegisNode;
import com.aegisos.node.api.ResponseWriter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ClusterHandler {

    private final AegisNode node;

    public ClusterHandler(AegisNode node) {
        this.node = node;
    }

    public void getHealth(HttpExchange exchange) throws IOException {
        NodeId leader = node.consensus().leaderId();
        if (leader == null) {
            ResponseWriter.writeError(exchange, 503, "Cluster is currently unavailable (No leader)");
        } else {
            ResponseWriter.writeJson(exchange, 200, new HealthResponse("UP"));
        }
    }

    public void getNodes(HttpExchange exchange) throws IOException {
        List<NodeResponse> nodes = new ArrayList<>();
        // Add self
        String selfId = com.aegisos.core.util.HexUtil.encode(node.identity().nodeId().toBytes());
        int selfApiPort = node.apiServer() != null ? node.apiServer().boundPort() : node.config().restPort();
        nodes.add(new NodeResponse(selfId, "ALIVE", selfApiPort));

        for (PeerEntry p : node.discovery().membership().allPeers()) {
            String id = com.aegisos.core.util.HexUtil.encode(p.getNodeId().toByteArray());
            if (!id.equals(selfId)) {
                nodes.add(new NodeResponse(id, p.getStatus().name(), 20001)); // Assuming default API port for now, can be extracted from PeerEntry if added to Gossip
            }
        }

        ResponseWriter.writeJson(exchange, 200, nodes);
    }

    public void getLeader(HttpExchange exchange) throws IOException {
        NodeId leader = node.consensus().leaderId();
        if (leader == null) {
            ResponseWriter.writeError(exchange, 503, "No leader currently elected");
            return;
        }
        String leaderId = com.aegisos.core.util.HexUtil.encode(leader.toBytes());

        // Return the leader ID and its API port
        // Currently, we don't propagate API port via Gossip, so we default to 20001 if it's not self
        int selfApiPort = node.apiServer() != null ? node.apiServer().boundPort() : node.config().restPort();
        int apiPort = com.aegisos.core.util.HexUtil.encode(node.identity().nodeId().toBytes()).equals(leaderId) ? selfApiPort : 20001;

        LeaderResponse response = new LeaderResponse(leaderId, apiPort);
        ResponseWriter.writeJson(exchange, 200, response);
    }
}

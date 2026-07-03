package com.aegisos.node.api.handlers;

import com.aegisos.api.dto.membership.MembershipRequest;
import com.aegisos.api.dto.membership.MembershipResponse;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.util.HexUtil;
import com.aegisos.node.AegisNode;
import com.aegisos.node.api.ResponseWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class AdminHandler {

    private static final Logger log = LoggerFactory.getLogger(AdminHandler.class);
    private final AegisNode node;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminHandler(AegisNode node) {
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

    public void addVoter(HttpExchange exchange) throws IOException {
        if (!checkLeader(exchange)) return;

        try {
            MembershipRequest req = mapper.readValue(exchange.getRequestBody(), MembershipRequest.class);
            if (req.nodeId == null || !req.nodeId.matches("^[0-9a-fA-F]+$") || req.nodeId.length() % 2 != 0) {
                ResponseWriter.writeError(exchange, 400, "INVALID_REQUEST");
                return;
            }

            byte[] payload = HexUtil.decode(req.nodeId);
            com.aegisos.proto.StateCommand cmd = com.aegisos.proto.StateCommand.newBuilder()
                    .setType(com.aegisos.proto.CommandType.ADD_VOTER)
                    .setPayload(ByteString.copyFrom(payload))
                    .build();

            node.consensus().propose(cmd).get(10, TimeUnit.SECONDS);

            ResponseWriter.writeJson(exchange, 202, new MembershipResponse("ACCEPTED", "Node " + req.nodeId + " proposed as voter"));
        } catch (Exception e) {
            log.error("Failed to add voter", e);
            ResponseWriter.writeError(exchange, 503, "SERVICE_UNAVAILABLE");
        }
    }

    public void removeVoter(HttpExchange exchange, String nodeId) throws IOException {
        if (!checkLeader(exchange)) return;

        try {
            if (nodeId == null || !nodeId.matches("^[0-9a-fA-F]+$") || nodeId.length() % 2 != 0) {
                ResponseWriter.writeError(exchange, 400, "INVALID_REQUEST");
                return;
            }

            byte[] payload = HexUtil.decode(nodeId);
            com.aegisos.proto.StateCommand cmd = com.aegisos.proto.StateCommand.newBuilder()
                    .setType(com.aegisos.proto.CommandType.REMOVE_VOTER)
                    .setPayload(ByteString.copyFrom(payload))
                    .build();

            node.consensus().propose(cmd).get(10, TimeUnit.SECONDS);

            ResponseWriter.writeJson(exchange, 202, new MembershipResponse("ACCEPTED", "Node " + nodeId + " proposed for removal"));
        } catch (Exception e) {
            log.error("Failed to remove voter", e);
            ResponseWriter.writeError(exchange, 503, "SERVICE_UNAVAILABLE");
        }
    }
}

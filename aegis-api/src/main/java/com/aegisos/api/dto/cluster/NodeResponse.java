package com.aegisos.api.dto.cluster;

public class NodeResponse {
    public String nodeId;
    public String status;
    public int apiPort;

    public NodeResponse() {
        this.nodeId = null;
        this.status = null;
        this.apiPort = 0;
    }

    public NodeResponse(String nodeId, String status, int apiPort) {
        this.nodeId = nodeId;
        this.status = status;
        this.apiPort = apiPort;
    }
}

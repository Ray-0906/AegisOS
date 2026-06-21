package com.aegisos.api.dto.cluster;

public class LeaderResponse {
    public final String leaderId;
    public final int apiPort;

    public LeaderResponse() {
        this.leaderId = null;
        this.apiPort = 0;
    }

    public LeaderResponse(String leaderId, int apiPort) {
        this.leaderId = leaderId;
        this.apiPort = apiPort;
    }
}

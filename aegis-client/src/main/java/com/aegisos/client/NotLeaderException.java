package com.aegisos.client;

public class NotLeaderException extends RuntimeException {
    private final String leaderId;
    private final int apiPort;

    public NotLeaderException(String leaderId, int apiPort) {
        super("Node is not the leader. Leader is: " + leaderId + " at port " + apiPort);
        this.leaderId = leaderId;
        this.apiPort = apiPort;
    }

    public String getLeaderId() {
        return leaderId;
    }

    public int getApiPort() {
        return apiPort;
    }
}

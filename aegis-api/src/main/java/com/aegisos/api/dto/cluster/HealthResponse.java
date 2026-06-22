package com.aegisos.api.dto.cluster;

public class HealthResponse {
    public final String status;

    public HealthResponse() {
        this.status = null;
    }

    public HealthResponse(String status) {
        this.status = status;
    }
}

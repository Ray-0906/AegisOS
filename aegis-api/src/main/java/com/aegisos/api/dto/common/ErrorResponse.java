package com.aegisos.api.dto.common;

public class ErrorResponse {
    public String error;
    public int code;
    public String leaderId;
    public Integer apiPort;

    public ErrorResponse() {
        this.error = null;
        this.code = 0;
        this.leaderId = null;
        this.apiPort = null;
    }

    public ErrorResponse(String error, int code) {
        this.error = error;
        this.code = code;
        this.leaderId = null;
        this.apiPort = null;
    }

    public ErrorResponse(String error, int code, String leaderId, Integer apiPort) {
        this.error = error;
        this.code = code;
        this.leaderId = leaderId;
        this.apiPort = apiPort;
    }
}

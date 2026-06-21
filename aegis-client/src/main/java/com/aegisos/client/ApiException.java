package com.aegisos.client;

public class ApiException extends RuntimeException {
    private final int statusCode;
    private final String errorBody;

    public ApiException(int statusCode, String errorBody) {
        super("API Error [" + statusCode + "]: " + errorBody);
        this.statusCode = statusCode;
        this.errorBody = errorBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorBody() {
        return errorBody;
    }
}

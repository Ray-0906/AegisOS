package com.aegisos.api.dto.file;

public class UploadFileResponse {
    public String path;
    public String status;
    public long bytes;
    public int chunkCount;

    public UploadFileResponse() {
        this.path = null;
        this.status = null;
        this.bytes = 0;
        this.chunkCount = 0;
    }

    public UploadFileResponse(String path, String status, long bytes, int chunkCount) {
        this.path = path;
        this.status = status;
        this.bytes = bytes;
        this.chunkCount = chunkCount;
    }
}

package com.aegisos.api.dto.artifact;

public class ArtifactUploadResponse {
    public final String artifactId;
    public final String name;
    public final long sizeBytes;

    public ArtifactUploadResponse() {
        this.artifactId = null;
        this.name = null;
        this.sizeBytes = 0;
    }

    public ArtifactUploadResponse(String artifactId, String name, long sizeBytes) {
        this.artifactId = artifactId;
        this.name = name;
        this.sizeBytes = sizeBytes;
    }
}

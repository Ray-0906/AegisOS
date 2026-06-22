package com.aegisos.api.dto.artifact;

public class ArtifactSummary {
    public String artifactId;
    public String name;
    public long sizeBytes;
    public long uploadedAt;

    public ArtifactSummary() {
        this.artifactId = null;
        this.name = null;
        this.sizeBytes = 0;
        this.uploadedAt = 0;
    }

    public ArtifactSummary(String artifactId, String name, long sizeBytes, long uploadedAt) {
        this.artifactId = artifactId;
        this.name = name;
        this.sizeBytes = sizeBytes;
        this.uploadedAt = uploadedAt;
    }
}

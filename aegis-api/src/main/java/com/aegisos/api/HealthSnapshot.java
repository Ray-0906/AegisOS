package com.aegisos.api;

public record HealthSnapshot(
    boolean discoveryOk,
    boolean consensusOk,
    boolean schedulerOk,
    boolean runtimeOk,
    boolean storageOk
) {
    public boolean isHealthy() {
        return discoveryOk && consensusOk && schedulerOk && runtimeOk && storageOk;
    }
}

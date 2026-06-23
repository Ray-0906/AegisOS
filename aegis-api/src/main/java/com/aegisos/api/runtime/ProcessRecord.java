package com.aegisos.api.runtime;

public record ProcessRecord(
        String processId,
        String artifactId,
        String ownerNodeId,
        String submitterNodeId,
        long executionId,
        ProcessState state,
        ProcessResources resources,
        long submitTimestamp,
        long stateTimestamp) {
}

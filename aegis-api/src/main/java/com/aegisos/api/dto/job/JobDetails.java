package com.aegisos.api.dto.job;

public record JobDetails(
        String id,
        String state,
        String node,
        long executionId,
        String error,
        JobResources resources
) {
}

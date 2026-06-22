package com.aegisos.api.dto.job;

public record JobSummary(
        String id,
        String state,
        String node,
        long executionId,
        String error
) {
}

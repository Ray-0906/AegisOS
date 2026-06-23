package com.aegisos.core.observability;

public record JobTimelineEvent(
    long timestampMs,
    JobEventType type,
    String nodeId,
    String details
) {
    public JobTimelineEvent {
        if (type == null) {
            throw new IllegalArgumentException("JobEventType cannot be null");
        }
    }
}

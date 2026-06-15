package com.aegisos.core.observability;

public enum JobEventType {
    SUBMITTED,
    QUEUED,
    ASSIGNED,
    STARTED,
    HEARTBEAT_LOST,
    COMPLETED,
    FAILED,
    FENCED
}

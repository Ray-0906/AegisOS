package com.aegisos.api.runtime;

public enum ProcessState {
    SUBMITTED,
    QUEUED,
    PLACED,
    STARTING,
    RUNNING,
    CHECKPOINTING,
    PAUSED,
    MIGRATING,
    COMPLETED,
    FAILED,
    CANCELLED
}

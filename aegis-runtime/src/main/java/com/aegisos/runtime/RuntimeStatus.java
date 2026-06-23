package com.aegisos.runtime;

/**
 * Status of an execution managed by a {@link RuntimeBackend}.
 */
public enum RuntimeStatus {
    STARTING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    UNKNOWN
}

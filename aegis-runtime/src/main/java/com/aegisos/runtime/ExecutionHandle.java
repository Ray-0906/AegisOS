package com.aegisos.runtime;

/**
 * Opaque handle to a running execution managed by a {@link RuntimeBackend}.
 * <p>
 * The {@code runtimeId} is an opaque identifier owned by the backend.
 * Callers must not interpret it or derive container/process state from it.
 */
public record ExecutionHandle(
    String runtimeId,
    String jobId,
    long executionId
) {}

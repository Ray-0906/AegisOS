package com.aegisos.runtime;

/**
 * Result of a completed execution. Contains only what both JVM and container
 * runtimes naturally produce: exit code and captured I/O streams.
 * <p>
 * JVM-specific artifacts (serialized return values, checkpoint state) are
 * managed by the JVM backend through existing mechanisms, not through this
 * shared abstraction.
 */
public record ExecutionResult(
    int exitCode,
    byte[] stdout,
    byte[] stderr
) {}

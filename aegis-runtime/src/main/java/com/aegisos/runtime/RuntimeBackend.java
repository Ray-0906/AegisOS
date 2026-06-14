package com.aegisos.runtime;

import com.aegisos.proto.JobRecord;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Abstraction over different job execution backends (JVM process, Docker
 * container, runc, etc.).
 * <p>
 * Each backend owns its internal state (process handles, container IDs) and
 * exposes only opaque {@link ExecutionHandle} references to callers.
 */
public interface RuntimeBackend {

    /**
     * Start execution of a job. Returns an opaque handle for lifecycle management.
     *
     * @param jobId       the job identifier
     * @param executionId the execution generation (for fencing)
     * @param record      the full job record including spec
     * @param workDir     the workspace root for this execution
     * @return an opaque handle to the running execution
     */
    ExecutionHandle start(String jobId, long executionId, JobRecord record,
                          Path workDir) throws Exception;

    /**
     * Forcibly kill a running execution.
     */
    void kill(ExecutionHandle handle);

    /**
     * Poll the current lifecycle status of an execution.
     */
    RuntimeStatus status(ExecutionHandle handle);

    /**
     * Attempt to collect the result of a finished execution.
     * Returns empty if the execution is still running.
     */
    Optional<ExecutionResult> tryCollect(ExecutionHandle handle);
}

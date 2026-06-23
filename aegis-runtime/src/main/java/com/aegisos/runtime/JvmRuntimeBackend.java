package com.aegisos.runtime;

import com.aegisos.core.identity.NodeId;
import com.aegisos.fs.AegisFS;
import com.aegisos.proto.JobRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JVM process execution backend. Thin adapter over the existing
 * {@link ProcessSupervisor} and {@link JobExecutor} infrastructure.
 * <p>
 * This class owns the mapping from opaque {@code runtimeId} to internal
 * {@link ProcessSupervisor} instances. No behavioral changes from the
 * pre-refactor execution model — the same control socket protocol,
 * checkpoint listener, and kill logic apply.
 */
public final class JvmRuntimeBackend implements RuntimeBackend {

    private static final Logger log = LoggerFactory.getLogger(JvmRuntimeBackend.class);

    private final JobExecutor executor;

    /**
     * Tracks active executions by runtimeId. The supervisor reference is used
     * for status polling, result collection, and kill operations.
     */
    private final Map<String, ActiveExecution> activeExecutions = new ConcurrentHashMap<>();

    public JvmRuntimeBackend(JobExecutor executor) {
        this.executor = executor;
    }

    @Override
    public ExecutionHandle start(String jobId, long executionId, JobRecord record,
                                  Path workDir) throws Exception {
        String runtimeId = "jvm-" + UUID.randomUUID();
        ActiveExecution exec = new ActiveExecution(jobId, executionId, runtimeId);
        activeExecutions.put(runtimeId, exec);
        log.debug("Registered JVM execution {} for job {} execution {}", runtimeId, jobId, executionId);
        return new ExecutionHandle(runtimeId, jobId, executionId);
    }

    @Override
    public void kill(ExecutionHandle handle) {
        ActiveExecution exec = activeExecutions.get(handle.runtimeId());
        if (exec != null) {
            log.info("Killing JVM execution {} for job {}", handle.runtimeId(), handle.jobId());
            executor.cancelJob(handle.jobId());
        }
    }

    @Override
    public RuntimeStatus status(ExecutionHandle handle) {
        ActiveExecution exec = activeExecutions.get(handle.runtimeId());
        if (exec == null) {
            return RuntimeStatus.UNKNOWN;
        }
        return exec.completed ? (exec.failed ? RuntimeStatus.FAILED : RuntimeStatus.COMPLETED) : RuntimeStatus.RUNNING;
    }

    @Override
    public Optional<ExecutionResult> tryCollect(ExecutionHandle handle) {
        ActiveExecution exec = activeExecutions.remove(handle.runtimeId());
        if (exec == null || !exec.completed) {
            return Optional.empty();
        }
        return Optional.of(new ExecutionResult(exec.exitCode, exec.stdout, exec.stderr));
    }

    /**
     * Execute a JVM job synchronously using the existing {@link JobExecutor}.
     * This is the main entry point called by {@code ProcessRuntimeAgent} for
     * JVM jobs. It delegates to {@code executor.run()} or
     * {@code executor.runFromArtifact()} exactly as the pre-refactor code did.
     *
     * @return the serialized job result (JVM-specific; not part of the shared
     *         {@link ExecutionResult} abstraction)
     */
    public byte[] executeSync(String jobId, long executionId, byte[] jobBytes,
                               byte[] restoreState, int memoryMb,
                               Map<String, String> mountPaths,
                               java.util.function.Consumer<byte[]> checkpointListener) throws Exception {
        return executor.run(jobId, executionId, jobBytes, restoreState, memoryMb,
                            mountPaths, checkpointListener);
    }

    /**
     * Execute an artifact-based JVM job synchronously.
     */
    public byte[] executeSyncFromArtifact(String jobId, long executionId,
                                           String artifactId, String fsPath,
                                           String className, String[] args,
                                           byte[] restoreState, int memoryMb,
                                           Map<String, String> mountPaths,
                                           java.util.function.Consumer<byte[]> checkpointListener) throws Exception {
        return executor.runFromArtifact(jobId, executionId, artifactId, fsPath,
                                        className, args, restoreState, memoryMb,
                                        mountPaths, checkpointListener);
    }

    /**
     * Cancel a job by delegating to the underlying {@link JobExecutor}.
     */
    public void cancelJob(String jobId) {
        executor.cancelJob(jobId);
    }

    /**
     * Close all active supervisors.
     */
    public void close() {
        activeExecutions.clear();
        executor.close();
    }

    // -- internal state tracking --

    private static final class ActiveExecution {
        final String jobId;
        final long executionId;
        final String runtimeId;
        volatile boolean completed;
        volatile boolean failed;
        volatile int exitCode;
        volatile byte[] stdout;
        volatile byte[] stderr;

        ActiveExecution(String jobId, long executionId, String runtimeId) {
            this.jobId = jobId;
            this.executionId = executionId;
            this.runtimeId = runtimeId;
        }
    }
}

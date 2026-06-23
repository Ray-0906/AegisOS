package com.aegisos.runtime.container;

import com.aegisos.proto.JobRecord;
import com.aegisos.runtime.ExecutionHandle;
import com.aegisos.runtime.ExecutionResult;
import com.aegisos.runtime.RuntimeBackend;
import com.aegisos.runtime.RuntimeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backend for executing containerized jobs.
 */
public class DockerRuntimeBackend implements RuntimeBackend {

    private static final Logger log = LoggerFactory.getLogger(DockerRuntimeBackend.class);

    private final ContainerEngine engine;
    private final ImageRegistry imageRegistry;

    /**
     * Map of runtimeId to active container details.
     */
    private final Map<String, ActiveContainer> activeContainers = new ConcurrentHashMap<>();

    public DockerRuntimeBackend(ContainerEngine engine, ImageRegistry imageRegistry) {
        this.engine = engine;
        this.imageRegistry = imageRegistry;
    }

    @Override
    public ExecutionHandle start(String jobId, long executionId, JobRecord record, Path workDir) throws Exception {
        String image = record.getSpec().getContainer().getImage();
        if (image.isEmpty()) {
            throw new IllegalArgumentException("Container image cannot be empty");
        }

        // Trust policy enforcement
        if (!imageRegistry.isTrusted(image)) {
            throw new SecurityException("Untrusted image: " + image);
        }

        String name = "aegis-job-" + jobId + "-" + executionId + "-" + UUID.randomUUID().toString().substring(0, 8);
        String runtimeId = "container-" + UUID.randomUUID().toString();

        log.info("Starting container {} for job {} execution {}", name, jobId, executionId);
        String containerId = engine.run(name, record.getSpec().getContainer(), record.getSpec().getResources(), workDir);

        activeContainers.put(runtimeId, new ActiveContainer(containerId, jobId, executionId));
        return new ExecutionHandle(runtimeId, jobId, executionId);
    }

    @Override
    public void kill(ExecutionHandle handle) {
        ActiveContainer ac = activeContainers.get(handle.runtimeId());
        if (ac != null) {
            log.info("Killing container {} for job {}", ac.containerId, handle.jobId());
            try {
                engine.stop(ac.containerId);
            } catch (Exception e) {
                log.error("Failed to stop container {}", ac.containerId, e);
            }
        }
    }

    public void cancelJob(String jobId) {
        for (ActiveContainer ac : activeContainers.values()) {
            if (ac.jobId.equals(jobId)) {
                log.info("Cancelling container {} for job {}", ac.containerId, jobId);
                try {
                    engine.stop(ac.containerId);
                } catch (Exception e) {
                    log.error("Failed to cancel container {}", ac.containerId, e);
                }
            }
        }
    }

    @Override
    public RuntimeStatus status(ExecutionHandle handle) {
        ActiveContainer ac = activeContainers.get(handle.runtimeId());
        if (ac == null) {
            return RuntimeStatus.UNKNOWN;
        }
        try {
            OptionalInt exitCode = engine.exitCode(ac.containerId);
            if (exitCode.isEmpty()) {
                return RuntimeStatus.RUNNING;
            }
            return exitCode.getAsInt() == 0 ? RuntimeStatus.COMPLETED : RuntimeStatus.FAILED;
        } catch (Exception e) {
            log.error("Failed to check status for container {}", ac.containerId, e);
            return RuntimeStatus.UNKNOWN;
        }
    }

    @Override
    public Optional<ExecutionResult> tryCollect(ExecutionHandle handle) {
        ActiveContainer ac = activeContainers.get(handle.runtimeId());
        if (ac == null) {
            return Optional.empty();
        }

        try {
            OptionalInt exitCode = engine.exitCode(ac.containerId);
            if (exitCode.isEmpty()) {
                return Optional.empty(); // Still running
            }

            // Finished, collect logs
            byte[] stdout = engine.stdout(ac.containerId);
            byte[] stderr = engine.stderr(ac.containerId);

            activeContainers.remove(handle.runtimeId());

            // Cleanup container
            try {
                engine.stop(ac.containerId);
            } catch (Exception ignored) {}

            return Optional.of(new ExecutionResult(exitCode.getAsInt(), stdout, stderr));

        } catch (Exception e) {
            log.error("Failed to collect result for container {}", ac.containerId, e);
            return Optional.empty();
        }
    }

    private static class ActiveContainer {
        final String containerId;
        final String jobId;
        final long executionId;

        ActiveContainer(String containerId, String jobId, long executionId) {
            this.containerId = containerId;
            this.jobId = jobId;
            this.executionId = executionId;
        }
    }
}

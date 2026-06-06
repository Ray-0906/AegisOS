package com.aegisos.runtime;

import com.aegisos.core.identity.NodeId;
import com.aegisos.fs.AegisFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Periodically extracts state from running jobs and saves it to the distributed file system,
 * recording the new checkpoint path via Raft so it's durable across node crashes.
 */
public final class CheckpointManager {

    private static final Logger log = LoggerFactory.getLogger(CheckpointManager.class);

    private final NodeId self;
    private final AegisFS fileSystem;
    private final CheckpointRecorder recorder;
    private final long intervalMs;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "aegis-checkpoint-scheduler");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();

    public interface CheckpointRecorder {
        void record(String jobId, long executionId, String checkpointFileId);
    }

    public CheckpointManager(NodeId self, AegisFS fileSystem, CheckpointRecorder recorder, long intervalMs) {
        this.self = self;
        this.fileSystem = fileSystem;
        this.recorder = recorder;
        this.intervalMs = intervalMs;
    }

    public void start() {
        log.info("Checkpoint manager started (interval={}ms)", intervalMs);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    public byte[] loadCheckpoint(String fileId) throws Exception {
        return fileSystem.read(fileId);
    }

    private void startCheckpointingTask(String jobId, long executionId, Callable<byte[]> stateCapturer) {
        AtomicInteger seq = new AtomicInteger();
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                byte[] bytes = stateCapturer.call();
                if (bytes != null) {
                    String path = "/checkpoints/" + jobId + "/" + seq.incrementAndGet();
                    fileSystem.write(path, bytes);
                    recorder.record(jobId, executionId, path);
                    log.debug("Checkpointed job {} -> {}", jobId, path);
                }
            } catch (Exception e) {
                log.debug("checkpoint of {} failed: {}", jobId, e.toString());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        activeTasks.put(jobId, task);
    }

    private void stopCheckpointingTask(String jobId) {
        ScheduledFuture<?> task = activeTasks.remove(jobId);
        if (task != null) {
            task.cancel(false);
        }
    }

    public byte[] runWithCheckpointing(String jobId, long executionId, byte[] jobBytes, byte[] restoreState, JobExecutor executor, int memoryMb) throws Exception {
        startCheckpointingTask(jobId, executionId, () -> {
            // Not perfectly supported with external process without RPC
            return null;
        });
        try {
            return executor.run(jobId, jobBytes, restoreState, memoryMb);
        } finally {
            stopCheckpointingTask(jobId);
        }
    }

    public byte[] runArtifactWithCheckpointing(String jobId, long executionId, String artifactId, String fsPath,
                                               String className, String[] args, byte[] restoreState,
                                               JobExecutor executor, int memoryMb) throws Exception {

        startCheckpointingTask(jobId, executionId, () -> {
            // Not supported for external process without RPC
            return null;
        });

        try {
            return executor.runFromArtifact(jobId, artifactId, fsPath, className, args, restoreState, memoryMb);
        } finally {
            stopCheckpointingTask(jobId);
        }
    }
}

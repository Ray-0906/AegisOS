package com.aegisos.runtime;

import com.aegisos.core.identity.NodeId;
import com.aegisos.fs.AegisFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.net.URLClassLoader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures periodic checkpoints of a running job to AegisFS and restores them on resume
 * (design section 3.7, Phase 6). Checkpoints are identified by their AegisFS path, which
 * is recorded in the job's Raft record via the supplied {@link CheckpointRecorder}.
 */
public final class CheckpointManager {

    private static final Logger log = LoggerFactory.getLogger(CheckpointManager.class);

    /** Records the latest checkpoint location for a job (typically an UPDATE_JOB proposal). */
    @FunctionalInterface
    public interface CheckpointRecorder {
        void record(String jobId, String checkpointPath);
    }

    private final AegisFS fileSystem;
    private final NodeId self;
    private final long intervalMs;
    private final CheckpointRecorder recorder;
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, r -> Thread.ofVirtual().unstarted(r));

    public CheckpointManager(AegisFS fileSystem, NodeId self, long intervalMs,
                             CheckpointRecorder recorder) {
        this.fileSystem = fileSystem;
        this.self = self;
        this.intervalMs = intervalMs;
        this.recorder = recorder;
    }

    public byte[] loadCheckpoint(String checkpointPath) {
        try {
            return fileSystem.read(checkpointPath);
        } catch (Exception e) {
            log.warn("could not load checkpoint {}: {}", checkpointPath, e.toString());
            return null;
        }
    }

    /**
     * Runs the job with periodic checkpointing. The job instance is kept live so its
     * {@link AegisJob#captureState()} can be invoked on a schedule and persisted to AegisFS.
     */
    public byte[] runWithCheckpointing(String jobId, byte[] jobBytes, byte[] restoreState,
                                       JobExecutor executor) throws Exception {
        AegisJob<?> job = Serialization.deserialize(jobBytes);
        if (job == null) {
            throw new IllegalArgumentException("job payload is empty");
        }
        if (restoreState != null && restoreState.length > 0) {
            job.restoreState(Serialization.deserialize(restoreState));
            log.info("Restored job {} from checkpoint", jobId);
        }

        AtomicInteger seq = new AtomicInteger();
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                Serializable state = job.captureState();
                if (state != null) {
                    byte[] bytes = Serialization.serialize(state);
                    String path = "/checkpoints/" + jobId + "/" + seq.incrementAndGet();
                    fileSystem.write(path, bytes);
                    recorder.record(jobId, path);
                    log.debug("Checkpointed job {} -> {}", jobId, path);
                }
            } catch (Exception e) {
                log.debug("checkpoint of {} failed: {}", jobId, e.toString());
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

        try {
            JobContext ctx = new JobContext(jobId, self, fileSystem);
            @SuppressWarnings("unchecked")
            AegisJob<Serializable> typed = (AegisJob<Serializable>) job;
            Serializable result = typed.execute(ctx);
            return Serialization.serialize(result);
        } finally {
            task.cancel(false);
        }
    }

    /** Artifact-based execution with checkpointing. */
    public byte[] runArtifactWithCheckpointing(String jobId, String artifactId, String fsPath,
                                               String className, String[] args, byte[] restoreState,
                                               ArtifactClassLoader artifactClassLoader) throws Exception {
        try (URLClassLoader cl = artifactClassLoader.createLoader(artifactId, fsPath)) {
            Class<?> clazz = Class.forName(className, true, cl);
            AegisJob<?> initJob;
            try {
                initJob = (AegisJob<?>) clazz.getConstructor(String[].class).newInstance((Object) args);
            } catch (NoSuchMethodException e) {
                initJob = (AegisJob<?>) clazz.getDeclaredConstructor().newInstance();
            }
            final AegisJob<?> job = initJob;

            if (restoreState != null && restoreState.length > 0) {
                Thread.currentThread().setContextClassLoader(cl);
                job.restoreState(Serialization.deserialize(restoreState));
                log.info("Restored artifact job {} from checkpoint", jobId);
            }

            AtomicInteger seq = new AtomicInteger();
            ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
                try {
                    Thread.currentThread().setContextClassLoader(cl); // ensure context for captureState
                    Serializable state = job.captureState();
                    if (state != null) {
                        byte[] bytes = Serialization.serialize(state);
                        String path = "/checkpoints/" + jobId + "/" + seq.incrementAndGet();
                        fileSystem.write(path, bytes);
                        recorder.record(jobId, path);
                        log.debug("Checkpointed artifact job {} -> {}", jobId, path);
                    }
                } catch (Exception e) {
                    log.debug("checkpoint of artifact job {} failed: {}", jobId, e.toString());
                }
            }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);

            try {
                JobContext ctx = new JobContext(jobId, self, fileSystem);
                @SuppressWarnings("unchecked")
                AegisJob<Serializable> typed = (AegisJob<Serializable>) job;
                Serializable result = typed.execute(ctx);
                return Serialization.serialize(result);
            } finally {
                task.cancel(false);
            }
        }
    }

    public void close() {
        scheduler.shutdownNow();
    }
}

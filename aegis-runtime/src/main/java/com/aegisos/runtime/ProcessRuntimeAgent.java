package com.aegisos.runtime;

import com.aegisos.consensus.ConsensusModule;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.message.AegisMessage;
import com.aegisos.core.message.MessageType;
import com.aegisos.fs.AegisFS;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import com.aegisos.proto.JobUpdate;
import com.aegisos.proto.RunJob;
import com.aegisos.proto.StateCommand;
import com.aegisos.runtime.Serialization;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aegisos.network.NetworkLayer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes jobs assigned to this node (design section 3.7). Receives RUN_JOB, runs the
 * job on a virtual thread, and records state transitions (RUNNING -> COMPLETED/FAILED)
 * in the Raft log so any node can observe progress and results.
 */
public final class ProcessRuntimeAgent {

    private static final Logger log = LoggerFactory.getLogger(ProcessRuntimeAgent.class);
    private static final int MAX_CONCURRENT_JOBS = 100;

    private final ConsensusModule consensus;
    private final NetworkLayer network;
    private final NodeId self;
    private final JobExecutor executor;
    private final AegisFS fileSystem;
    private final ArtifactRegistry artifactRegistry;
    private final ArtifactClassLoader artifactClassLoader;
    private final JobRegistry registry = new JobRegistry();
    private final AtomicInteger running = new AtomicInteger(0);
    public final java.util.concurrent.atomic.AtomicLong jobsStarted = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong jobsCompleted = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong jobsFailed = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong jobsCancelled = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong jobsSuperseded = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong fencingDrops = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong logUploadsSucceeded = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong logUploadsFailed = new java.util.concurrent.atomic.AtomicLong(0);
    private CheckpointManager checkpointManager; // set by Phase 6 wiring
    private volatile boolean shuttingDown = false;

    public ProcessRuntimeAgent(ConsensusModule consensus, NetworkLayer network, NodeId self, AegisFS fileSystem,
                               ArtifactRegistry artifactRegistry, ArtifactClassLoader artifactClassLoader) {
        this.consensus = consensus;
        this.network = network;
        this.self = self;
        this.artifactRegistry = artifactRegistry;
        this.artifactClassLoader = artifactClassLoader;
        this.executor = new JobExecutor(self, fileSystem, artifactClassLoader);
        this.fileSystem = fileSystem;
    }

    public void registerAppliers() {
        registry.registerWith(consensus.stateMachine());
    }

    public void start() {
        // RUN_JOB is delivered via the network handler installed below.
        network.registerHandler(MessageType.CANCEL_JOB, msg -> {
            try {
                String jobId = new String(msg.payload(), java.nio.charset.StandardCharsets.UTF_8);
                killProcessLocal(jobId);
            } catch (Exception e) {
                log.error("Failed to process CANCEL_JOB", e);
            }
            return null;
        });
        log.info("Process runtime agent started");
    }

    public AegisMessage onRunJob(AegisMessage msg) {
        if (shuttingDown) {
            log.info("Agent is shutting down, dropping RUN_JOB message.");
            return null;
        }
        try {
            RunJob runJob = RunJob.parseFrom(msg.payload());
            dispatchLocal(runJob.getRecord());
        } catch (Exception e) {
            log.error("Failed to parse or dispatch RUN_JOB", e);
        }
        return null;
    }

    /** Starts executing an assigned job on a virtual thread (also used for self-assignment). */
    public void dispatchLocal(JobRecord record) {
        if (shuttingDown) {
            log.info("Agent is shutting down, dropping dispatch for job {}", record.getSpec().getJobId());
            return;
        }
        Thread.ofVirtual().name("aegis-job-" + record.getSpec().getJobId())
                .start(() -> executeJob(record));
    }
    
    public void cancelJob(String jobId) {
        registry.get(jobId).ifPresentOrElse(job -> {
            if (!registry.isTerminal(jobId)) {
                update(jobId, job.getExecutionId(), JobState.CANCELLED, null, "user-cancelled");
                jobsCancelled.incrementAndGet();
            }
            NodeId assignedNode = NodeId.of(job.getAssignedNodeId().toByteArray());
            if (self.equals(assignedNode)) {
                killProcessLocal(jobId);
            } else {
                log.info("Forwarding cancel for job {} to node {}", jobId, assignedNode.shortId());
                network.sendAsync(assignedNode, MessageType.CANCEL_JOB, jobId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }, () -> {
            log.warn("Cannot cancel unknown job {}", jobId);
            killProcessLocal(jobId); // Try locally anyway just in case
        });
    }

    private void killProcessLocal(String jobId) {
        log.info("Killing process for job {} locally", jobId);
        executor.cancelJob(jobId);
    }

    public void setCheckpointManager(CheckpointManager checkpointManager) {
        this.checkpointManager = checkpointManager;
    }

    public JobRegistry registry() {
        return registry;
    }

    public ConsensusModule consensus() {
        return consensus;
    }

    public int runningJobs() {
        return running.get();
    }

    public boolean canAccept() {
        boolean accept = running.get() < MAX_CONCURRENT_JOBS;
        log.info("canAccept() returning {} (running={})", accept, running.get());
        return accept;
    }

    /**
     * Returns true if a newer execution has been registered for the given job,
     * meaning this execution is superseded and must not publish state transitions.
     */
    private boolean isSuperseded(String jobId, long myExecutionId) {
        return registry.get(jobId)
                .map(r -> r.getExecutionId() > myExecutionId)
                .orElse(false);
    }

    private void executeJob(JobRecord record) {
        String jobId = record.getSpec().getJobId();
        long executionId = record.getExecutionId();
        running.incrementAndGet();
        
        java.util.concurrent.ScheduledExecutorService hbScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        hbScheduler.scheduleAtFixedRate(() -> {
            try {
                com.aegisos.core.identity.NodeId leader = consensus.leaderId();
                if (leader != null) {
                    com.aegisos.proto.JobHeartbeat hb = com.aegisos.proto.JobHeartbeat.newBuilder()
                            .setJobId(jobId)
                            .setExecutionId(executionId)
                            .build();
                    network.sendAsync(leader, com.aegisos.core.message.MessageType.JOB_HEARTBEAT, hb.toByteArray());
                }
            } catch (Exception e) {
                log.debug("Failed to send heartbeat for job {}: {}", jobId, e.getMessage());
            }
        }, 0, 5, java.util.concurrent.TimeUnit.SECONDS);

        try {
            update(jobId, executionId, JobState.RUNNING, null, null);
            jobsStarted.incrementAndGet();

            byte[] restoreState = null;
            if (checkpointManager != null && !record.getCheckpointFileId().isEmpty()) {
                restoreState = checkpointManager.loadCheckpoint(record.getCheckpointFileId());
            }

            byte[] result;
            int memoryMb = (int) record.getSpec().getResources().getMemoryMb();
            String artifactId = record.getSpec().getCodeFileId();
            if (!artifactId.isEmpty()) {
                com.aegisos.proto.ArtifactRecord artifact = artifactRegistry.bySha256(artifactId)
                        .orElseThrow(() -> new IllegalStateException("unknown artifact: " + artifactId));
                String className = record.getSpec().getClassName();
                String[] args = Serialization.deserialize(record.getSpec().getArgs().toByteArray());
                
                if (checkpointManager != null) {
                    result = checkpointManager.runArtifactWithCheckpointing(jobId, executionId, artifactId,
                            artifact.getFsPath(), className, args, restoreState, executor, memoryMb);
                } else {
                    result = executor.runFromArtifact(jobId, artifactId, artifact.getFsPath(),
                            className, args, restoreState, memoryMb);
                }
            } else {
                if (checkpointManager != null) {
                    result = checkpointManager.runWithCheckpointing(jobId, executionId,
                            record.getSpec().getArgs().toByteArray(), restoreState, executor, memoryMb);
                } else {
                    result = executor.run(jobId, record.getSpec().getArgs().toByteArray(), restoreState, memoryMb);
                }
            }

            // Gate A: check fencing before publishing state
            if (isSuperseded(jobId, executionId)) {
                fencingDrops.incrementAndGet();
                jobsSuperseded.incrementAndGet();
                log.warn("Execution {} of job {} superseded, discarding result", executionId, jobId);
                return;
            }
            // Gate B: upload job logs to AegisFS (fenced by executionId)
            uploadJobLogs(jobId, executionId);
            update(jobId, executionId, JobState.COMPLETED, result, null);
            jobsCompleted.incrementAndGet();
            log.info("Job {} COMPLETED", jobId);
        } catch (Exception e) {
            if (shuttingDown) {
                log.info("Job {} aborted due to node shutdown, ignoring failure so it can be marked LOST.", jobId);
            } else {
                if (!isSuperseded(jobId, executionId)) {
                    log.error("Job {} FAILED", jobId, e);
                    uploadJobLogs(jobId, executionId);
                    update(jobId, executionId, JobState.FAILED, null, e.getMessage() == null ? "error" : e.getMessage());
                    jobsFailed.incrementAndGet();
                } else {
                    fencingDrops.incrementAndGet();
                    jobsSuperseded.incrementAndGet();
                    log.warn("Execution {} of job {} superseded (failed path), discarding", executionId, jobId);
                }
            }
        } finally {
            hbScheduler.shutdownNow();
            running.decrementAndGet();
            cleanupJobFiles(jobId);
        }
    }

    private void update(String jobId, long executionId, JobState state, byte[] result, String error) {
        JobUpdate.Builder b = JobUpdate.newBuilder()
                .setJobId(jobId)
                .setExecutionId(executionId)
                .setState(state);
        if (result != null) b.setResult(ByteString.copyFrom(result));
        if (error != null) b.setError(error);

        StateCommand cmd = StateCommand.newBuilder()
                .setType(CommandType.UPDATE_JOB)
                .setPayload(b.build().toByteString())
                .build();
        try {
            consensus.propose(cmd).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("Failed to commit {} for job {}", state, jobId, e);
        }
    }

    /**
     * Uploads stdout/stderr from the worker's temporary directory to AegisFS.
     * Gate B: only uploads if this execution has not been superseded.
     */
    private void uploadJobLogs(String jobId, long executionId) {
        if (isSuperseded(jobId, executionId)) {
            fencingDrops.incrementAndGet();
            log.warn("Execution {} of job {} superseded, skipping log upload (Gate B)", executionId, jobId);
            return;
        }
        java.nio.file.Path workDir = java.nio.file.Paths.get(
                System.getProperty("java.io.tmpdir"), "aegis", self.shortId(), "jobs", jobId);
        uploadLogFile(workDir.resolve("stdout.log"), jobId, executionId, "stdout");
        uploadLogFile(workDir.resolve("stderr.log"), jobId, executionId, "stderr");
    }

    private void uploadLogFile(java.nio.file.Path localFile, String jobId, long executionId, String name) {
        try {
            boolean exists = java.nio.file.Files.exists(localFile);
            long size = exists ? java.nio.file.Files.size(localFile) : -1;
            log.info("uploadLogFile: localFile={}, exists={}, size={}", localFile.toAbsolutePath(), exists, size);
            if (exists) {
                byte[] data = java.nio.file.Files.readAllBytes(localFile);
                if (data.length > 0) {
                    String fsPath = "/jobs/" + jobId + "/" + executionId + "/" + name;
                    fileSystem.write(fsPath, data);
                    logUploadsSucceeded.incrementAndGet();
                    log.info("Uploaded job {} execution {} {} ({} bytes) -> {}",
                            jobId, executionId, name, data.length, fsPath);
                } else {
                    log.warn("uploadLogFile: localFile={} exists but has size 0", localFile.toAbsolutePath());
                }
            } else {
                log.warn("uploadLogFile: localFile={} does not exist", localFile.toAbsolutePath());
            }
        } catch (Exception e) {
            logUploadsFailed.incrementAndGet();
            log.error("Failed to upload {} for job {}: {}", name, jobId, e.getMessage(), e);
        }
    }

    private void cleanupJobFiles(String jobId) {
        java.nio.file.Path workDir = java.nio.file.Paths.get(
                System.getProperty("java.io.tmpdir"), "aegis", self.shortId(), "jobs", jobId);
        try {
            if (java.nio.file.Files.exists(workDir)) {
                try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(workDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .map(java.nio.file.Path::toFile)
                        .forEach(java.io.File::delete);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to cleanup workdir {}", workDir, e);
        }
    }

    public void close() {
        log.info("ProcessRuntimeAgent shutting down");
        shuttingDown = true;
        // Cancel all active jobs to ensure child processes are killed
        executor.close();
    }
}

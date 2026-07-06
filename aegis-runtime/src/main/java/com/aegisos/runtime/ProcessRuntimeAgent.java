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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.io.File;
import java.nio.file.Files;
import java.io.IOException;

import com.aegisos.runtime.container.ContainerEngine;
import com.aegisos.runtime.container.ImageRegistry;
import com.aegisos.runtime.container.DockerRuntimeBackend;

/**
 * Executes jobs assigned to this node (design section 3.7). Receives RUN_JOB, runs the
 * job on a virtual thread, and records state transitions (RUNNING -> COMPLETED/FAILED)
 * in the Raft log so any node can observe progress and results.
 */
public final class ProcessRuntimeAgent implements com.aegisos.scheduler.LocalityProvider {

    private static final Logger log = LoggerFactory.getLogger(ProcessRuntimeAgent.class);
    private static final int MAX_CONCURRENT_JOBS = 100;
    private static final long UPDATE_RETRY_WINDOW_MS = 30_000;
    private static final long UPDATE_RETRY_DELAY_MS = 250;
    private static final long CHECKPOINT_FAILURE_LOG_INTERVAL_MS = 5_000;

    private final ConsensusModule consensus;
    private final NetworkLayer network;
    private final NodeId self;

    private final JobExecutor executor;
    private final JvmRuntimeBackend jvmBackend;
    private ContainerEngine containerEngine;
    private ImageRegistry imageRegistry;
    private DockerRuntimeBackend dockerBackend;
    private final AegisFS fileSystem;
    private final ArtifactRegistry artifactRegistry;
    private final ArtifactClassLoader artifactClassLoader;
    private final com.aegisos.core.observability.MetricsRegistry metricsRegistry;
    private final JobRegistry registry;
    private final AtomicInteger running = new AtomicInteger(0);
    private final ScheduledExecutorService cleanupExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aegis-workspace-cleanup");
                t.setDaemon(true);
                return t;
            });
    /**
     * Shared scheduler for job heartbeats: one daemon platform thread for the whole agent
     * instead of one non-daemon platform executor per job. Per-job executors were shut down
     * only in the job thread's finally block, so any stalled job leaked a non-daemon thread
     * that prevented JVM exit and re-dialed the leader every 5 seconds forever.
     */
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aegis-job-heartbeat");
                t.setDaemon(true);
                return t;
            });
    public final java.util.concurrent.atomic.AtomicLong jobsStarted = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong jobsCompleted = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong jobsFailed = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong jobsCancelled = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong jobsSuperseded = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong fencingDrops = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong logUploadsSucceeded = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong logUploadsFailed = new java.util.concurrent.atomic.AtomicLong(0);

    public enum OwnershipDecision {
        GRANTED,
        DENIED
    }

    public enum PublicationResult {
        ACKNOWLEDGED,
        UNKNOWN
    }

    public enum ExecutionKind {
        CONTAINER,
        JVM
    }

    public record LocalExecution(
            long executionId,
            ExecutionKind kind,
            java.time.Instant startedAt,
            boolean runningAcknowledged
    ) {}

    private final java.util.concurrent.ConcurrentMap<String, LocalExecution> activeLocalWorkloads =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final TerminalPublicationScheduler terminalScheduler;

    private volatile boolean shuttingDown = false;
    private final java.nio.file.Path workspaceRoot;
    private final int workspaceCleanupDelaySeconds;

    public ProcessRuntimeAgent(ConsensusModule consensus, NetworkLayer network, NodeId self, AegisFS fileSystem,
                               ArtifactRegistry artifactRegistry, ArtifactClassLoader artifactClassLoader, java.nio.file.Path workspaceRoot,
                               int workspaceCleanupDelaySeconds, com.aegisos.core.observability.MetricsRegistry metricsRegistry,
                               com.aegisos.core.observability.TimelineRegistry timelineRegistry) {
        this.consensus = consensus;
        this.network = network;
        this.self = self;
        this.artifactRegistry = artifactRegistry;
        this.artifactClassLoader = artifactClassLoader;
        this.workspaceRoot = workspaceRoot;
        this.workspaceCleanupDelaySeconds = workspaceCleanupDelaySeconds;
        this.metricsRegistry = metricsRegistry;
        this.registry = new JobRegistry(metricsRegistry, timelineRegistry);
        this.executor = new JobExecutor(self, fileSystem, artifactClassLoader, workspaceRoot);
        this.jvmBackend = new JvmRuntimeBackend(this.executor);
        this.containerEngine = new com.aegisos.runtime.container.DockerEngine();
        this.imageRegistry = new com.aegisos.runtime.container.MemoryImageRegistry();
        this.dockerBackend = new com.aegisos.runtime.container.DockerRuntimeBackend(this.containerEngine, this.imageRegistry);
        this.fileSystem = fileSystem;
        this.terminalScheduler = new TerminalPublicationScheduler(consensus, this::isSuperseded);

        // Startup crash cleanup
        try {
            if (Files.exists(workspaceRoot)) {
                try (java.util.stream.Stream<java.nio.file.Path> stream = Files.walk(workspaceRoot)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                          .map(java.nio.file.Path::toFile)
                          .forEach(File::delete);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to clean up stale workspaces on startup", e);
        }
    }

    public void registerAppliers() {
        registry.registerWith(consensus.stateMachine());
    }

    public void setContainerEngine(ContainerEngine engine) {
        this.containerEngine = engine;
        this.dockerBackend = new com.aegisos.runtime.container.DockerRuntimeBackend(engine, this.imageRegistry);
    }

    public ImageRegistry getImageRegistry() {
        return imageRegistry;
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
        log.debug("Process runtime agent started");
    }

    public AegisMessage onRunJob(AegisMessage msg) {
        if (shuttingDown) {
            log.debug("Agent is shutting down, dropping RUN_JOB message.");
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
        String jobId = record.getSpec().getJobId();
        long executionId = record.getExecutionId();
        if (shuttingDown) {
            log.debug("Agent is shutting down, dropping dispatch for job {}", jobId);
            return;
        }

        // Packet Fence: Cheap rejection of obviously stale RUN_JOB packets.
        if (isSuperseded(jobId, executionId)) {
            fencingDrops.incrementAndGet();
            log.warn("Packet Fence: dropping stale RUN_JOB for job {} executionId={}", jobId, executionId);
            return;
        }

        Thread.ofVirtual().name("aegis-job-" + jobId)
                .start(() -> executeJob(record));
    }
    
    public void cancelJob(String jobId) {
        registry.get(jobId).ifPresentOrElse(job -> {
            if (!registry.isTerminal(jobId)) {
                terminalScheduler.enqueue(jobId, job.getExecutionId(), JobState.CANCELLED, null, "user-cancelled");
                jobsCancelled.incrementAndGet();
            }
            NodeId assignedNode = NodeId.of(job.getAssignedNodeId().toByteArray());
            if (self.equals(assignedNode)) {
                killProcessLocal(jobId);
            } else {
                log.debug("Forwarding cancel for job {} to node {}", jobId, assignedNode.shortId());
                network.sendAsync(assignedNode, MessageType.CANCEL_JOB, jobId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }, () -> {
            log.warn("Cannot cancel unknown job {}", jobId);
            killProcessLocal(jobId); // Try locally anyway just in case
        });
    }

    public void killProcessLocal(String jobId) {
        log.debug("Killing process for job {} locally", jobId);
        jvmBackend.cancelJob(jobId);
        if (dockerBackend != null) {
            dockerBackend.cancelJob(jobId);
        }
    }


    public JobRegistry registry() {
        return registry;
    }

    public ArtifactRegistry artifactRegistry() {
        return artifactRegistry;
    }

    public ConsensusModule consensus() {
        return consensus;
    }

    @Override
    public int getRunningJobs() {
        return running.get();
    }

    @Override
    public long getDownloadBytesSaved(java.util.List<String> artifactSha256s, String checkpointFileId) {
        long saved = 0;
        for (String sha : artifactSha256s) {
            saved += artifactClassLoader.getCache().getCachedSizeBytes(sha);
        }
        if (checkpointFileId != null && !checkpointFileId.isEmpty()) {
            java.util.Optional<com.aegisos.proto.FileMetadata> opt = fileSystem.fileIndex().byName(checkpointFileId);
            if (opt.isPresent()) {
                com.aegisos.proto.FileMetadata meta = opt.get();
                boolean allLocal = true;
                for (com.aegisos.proto.ChunkRef ref : meta.getChunksList()) {
                    boolean hasSelf = false;
                    for (com.google.protobuf.ByteString nodeBytes : ref.getNodeIdsList()) {
                        if (NodeId.of(nodeBytes.toByteArray()).equals(self)) {
                            hasSelf = true;
                            break;
                        }
                    }
                    if (!hasSelf) {
                        allLocal = false;
                        break;
                    }
                }
                if (allLocal) {
                    saved += meta.getSize();
                }
            }
        }
        return saved;
    }

    public boolean canAccept() {
        boolean accept = running.get() < MAX_CONCURRENT_JOBS;
        log.trace("canAccept() returning {} (running={})", accept, running.get());
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

    private boolean isCurrentState(String jobId, long executionId, JobState state) {
        return registry.get(jobId)
                .map(r -> r.getExecutionId() == executionId && r.getState() == state)
                .orElse(false);
    }

    private void executeJob(JobRecord record) {
        com.aegisos.proto.RuntimeType runtime = record.getSpec().getRuntime();
        if (runtime == com.aegisos.proto.RuntimeType.RUNTIME_CONTAINER) {
            executeContainerJob(record);
        } else {
            String artifactId = record.getSpec().getCodeFileId();
            if (artifactId != null && !artifactId.isEmpty()) {
                java.util.Optional<com.aegisos.proto.ArtifactRecord> opt = artifactRegistry.bySha256(artifactId);
                if (opt.isPresent() && opt.get().getFileName().endsWith(".js")) {
                    executeNodeJob(record, opt.get());
                    return;
                }
            }
            executeJvmJob(record);
        }
    }

    private void executeNodeJob(JobRecord record, com.aegisos.proto.ArtifactRecord artifact) {
        String jobId = record.getSpec().getJobId();
        long executionId = record.getExecutionId();
        if (shuttingDown) {
            log.debug("Skipping node job {} execution {} because runtime is shutting down", jobId, executionId);
            return;
        }
        running.incrementAndGet();

        java.util.concurrent.ScheduledFuture<?> heartbeatTask;
        try {
            heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    com.aegisos.core.identity.NodeId leader = consensus.leaderId();
                    if (leader != null) {
                        com.aegisos.proto.JobHeartbeat hb = com.aegisos.proto.JobHeartbeat.newBuilder()
                                .setJobId(jobId)
                                .setExecutionId(executionId)
                                .build();
                        network.sendAsync(leader, com.aegisos.core.message.MessageType.JOB_HEARTBEAT, hb.toByteArray());
                    }
                } catch (Exception e) {}
            }, 0, 5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            running.decrementAndGet();
            return;
        }

        Thread runner = new Thread(() -> {
            try {
                artifactClassLoader.getCache().pin(artifact.getArtifactId());
                java.nio.file.Path localPath = artifactClassLoader.getCache().resolve(artifact.getArtifactId(), artifact.getFsPath());
                
                String[] args = Serialization.deserialize(record.getSpec().getArgs().toByteArray());
                java.util.List<String> command = new java.util.ArrayList<>();
                command.add("node");
                command.add(localPath.toAbsolutePath().toString());
                if (args != null) {
                    for (String arg : args) {
                        command.add(arg);
                    }
                }
                
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.environment().put("PORT", String.valueOf(8080));
                pb.environment().put("AEGIS_NODE_ID", self.toHex());
                
                java.nio.file.Path execRoot = workspaceRoot.resolve(jobId).resolve("exec-" + executionId);
                WorkspaceInfo workspace = new WorkspaceInfo(execRoot);
                workspace.provision();
                pb.redirectOutput(workspace.stdoutLog().toFile());
                pb.redirectError(workspace.stderrLog().toFile());

                Process process = pb.start();

                // 1. Workload Reality Context
                activeLocalWorkloads.put(jobId, new LocalExecution(executionId, ExecutionKind.JVM, java.time.Instant.now(), false));
                jobsStarted.incrementAndGet();

                // 2. Metadata Publication — transition QUEUED -> RUNNING
                PublicationResult pub = publishState(jobId, executionId, JobState.RUNNING, null, null);
                if (pub == PublicationResult.ACKNOWLEDGED) {
                    activeLocalWorkloads.computeIfPresent(jobId, (k, v) ->
                            new LocalExecution(v.executionId(), v.kind(), v.startedAt(), true));
                    if (metricsRegistry != null) metricsRegistry.counter("aegis_running_acknowledged").increment();
                } else {
                    if (metricsRegistry != null) metricsRegistry.counter("aegis_running_unknown").increment();
                }

                int exitCode = process.waitFor();
                
                if (isSuperseded(jobId, executionId)) {
                    fencingDrops.incrementAndGet();
                    jobsSuperseded.incrementAndGet();
                    return;
                }
                
                if (exitCode == 0) {
                    terminalScheduler.enqueue(jobId, executionId, com.aegisos.proto.JobState.COMPLETED, null, null);
                    jobsCompleted.incrementAndGet();
                    log.info("Node Job {} COMPLETED", jobId);
                } else {
                    terminalScheduler.enqueue(jobId, executionId, com.aegisos.proto.JobState.FAILED, null, "Exit code " + exitCode);
                    jobsFailed.incrementAndGet();
                    log.error("Node Job {} FAILED with exit code {}", jobId, exitCode);
                }
                uploadJobLogsBestEffort(jobId, executionId);
            } catch (Exception e) {
                if (!isSuperseded(jobId, executionId)) {
                    terminalScheduler.enqueue(jobId, executionId, com.aegisos.proto.JobState.FAILED, null, e.getMessage());
                    jobsFailed.incrementAndGet();
                    log.error("Node Job {} FAILED", jobId, e);
                    uploadJobLogsBestEffort(jobId, executionId);
                }
            } finally {
                heartbeatTask.cancel(true);
                running.decrementAndGet();
                activeLocalWorkloads.remove(jobId);
                artifactClassLoader.getCache().unpin(artifact.getArtifactId());
            }
        }, "aegis-node-job-" + jobId);
        
        runner.setDaemon(true);
        runner.start();
    }

    private void executeContainerJob(JobRecord record) {
        String jobId = record.getSpec().getJobId();
        long executionId = record.getExecutionId();
        if (shuttingDown) {
            log.debug("Skipping container job {} execution {} because runtime is shutting down", jobId, executionId);
            return;
        }
        running.incrementAndGet();

        java.util.concurrent.ScheduledFuture<?> heartbeatTask;
        try {
            heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
                try {
                    com.aegisos.core.identity.NodeId leader = consensus.leaderId();
                    if (leader != null) {
                        com.aegisos.proto.JobHeartbeat hb = com.aegisos.proto.JobHeartbeat.newBuilder()
                                .setJobId(jobId)
                                .setExecutionId(executionId)
                                .build();
                        network.sendAsync(leader, com.aegisos.core.message.MessageType.JOB_HEARTBEAT, hb.toByteArray());
                    }
                } catch (Exception e) {}
            }, com.aegisos.core.SchedulerJitter.jitter(0, 5), 5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            running.decrementAndGet();
            return;
        }

        try {
            // 1. Workload Fence (Gate B)
            if (validateOwnershipBeforeSideEffect(jobId, executionId, "container-start") == OwnershipDecision.DENIED) {
                return;
            }

            java.nio.file.Path execRoot = workspaceRoot.resolve(jobId).resolve("exec-" + executionId);
            java.nio.file.Files.createDirectories(execRoot);

            // 2. Workload Reality
            ExecutionHandle handle = dockerBackend.start(jobId, executionId, record, execRoot);
            activeLocalWorkloads.put(jobId, new LocalExecution(executionId, ExecutionKind.CONTAINER, java.time.Instant.now(), false));
            jobsStarted.incrementAndGet();

            // 3. Metadata Publication
            PublicationResult pub = publishState(jobId, executionId, JobState.RUNNING, null, null);
            if (pub == PublicationResult.ACKNOWLEDGED) {
                activeLocalWorkloads.computeIfPresent(jobId, (k, v) ->
                        new LocalExecution(v.executionId(), v.kind(), v.startedAt(), true));
                if (metricsRegistry != null) metricsRegistry.counter("aegis_running_acknowledged").increment();
            } else {
                if (metricsRegistry != null) metricsRegistry.counter("aegis_running_unknown").increment();
            }

            while (!shuttingDown) {
                RuntimeStatus status = dockerBackend.status(handle);
                if (status == RuntimeStatus.COMPLETED || status == RuntimeStatus.FAILED) {
                    
                    if (isSuperseded(jobId, executionId)) {
                        fencingDrops.incrementAndGet();
                        jobsSuperseded.incrementAndGet();
                        if (metricsRegistry != null) metricsRegistry.counter("aegis_execution_fencing_total").increment();
                        log.warn("Execution {} of job {} superseded, discarding result", executionId, jobId);
                        return;
                    }

                    java.util.Optional<com.aegisos.runtime.ExecutionResult> resultOpt = dockerBackend.tryCollect(handle);
                    if (resultOpt.isPresent()) {
                        com.aegisos.runtime.ExecutionResult res = resultOpt.get();
                        if (status == RuntimeStatus.COMPLETED) {
                            terminalScheduler.enqueue(jobId, executionId, JobState.COMPLETED, null, null);
                            jobsCompleted.incrementAndGet();
                            log.info("Container job {} COMPLETED (queued for publication)", jobId);
                        } else {
                            if (isCurrentState(jobId, executionId, JobState.CANCELLED)) {
                                log.info("Execution {} of job {} exited after cancellation; preserving CANCELLED state", executionId, jobId);
                            } else {
                                terminalScheduler.enqueue(jobId, executionId, JobState.FAILED, null, new String(res.stderr()));
                                jobsFailed.incrementAndGet();
                                log.info("Container job {} FAILED (queued for publication)", jobId);
                            }
                        }
                    } else {
                        if (!isCurrentState(jobId, executionId, JobState.CANCELLED)) {
                            terminalScheduler.enqueue(jobId, executionId, JobState.FAILED, null, "Failed to collect container result");
                            jobsFailed.incrementAndGet();
                        }
                    }
                    
                    uploadJobLogsBestEffort(jobId, executionId);
                    return;
                }
                Thread.sleep(100);
            }
        } catch (Exception e) {
            log.error("Error monitoring docker job {}", jobId, e);
            terminalScheduler.enqueue(jobId, executionId, JobState.FAILED, null, e.getMessage() == null ? "error" : e.getMessage());
            jobsFailed.incrementAndGet();
        } finally {
            heartbeatTask.cancel(true);
            running.decrementAndGet();
            activeLocalWorkloads.remove(jobId);
            cleanupJobFiles(jobId, executionId);
        }
    }

    private void executeJvmJob(JobRecord record) {
        String jobId = record.getSpec().getJobId();
        long executionId = record.getExecutionId();
        if (shuttingDown) {
            log.debug("Skipping job {} execution {} because runtime is shutting down", jobId, executionId);
            return;
        }
        running.incrementAndGet();

        java.util.concurrent.ScheduledFuture<?> heartbeatTask;
        try {
            heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(() -> {
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
            }, com.aegisos.core.SchedulerJitter.jitter(0, 5), 5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            running.decrementAndGet();
            if (shuttingDown) {
                log.debug("Heartbeat scheduler stopped while starting job {} execution {}; job skipped",
                        jobId, executionId);
            } else {
                log.warn("Heartbeat scheduler rejected job {} execution {}; job skipped",
                        jobId, executionId, e);
            }
            return;
        }

        try {
            byte[] restoreState = null;
            if (!record.getCheckpointFileId().isEmpty()) {
                if (!update(jobId, executionId, JobState.RESTORING, null, null)) {
                    if (metricsRegistry != null) metricsRegistry.counter("aegis_execution_fencing_total").increment();
                    log.warn("Execution {} of job {} failed to transition to RESTORING (likely superseded), discarding", executionId, jobId);
                    return;
                }
                try {
                    restoreState = fileSystem.read(record.getCheckpointFileId());
                } catch (Exception e) {
                    log.error("Failed to load state for {}", jobId, e);
                    terminalScheduler.enqueue(jobId, executionId, JobState.FAILED, null, "Failed to load checkpoint: " + e.getMessage());
                    jobsFailed.incrementAndGet();
                    return;
                }
            }

            // 1. Workload Fence (Gate B)
            if (validateOwnershipBeforeSideEffect(jobId, executionId, "jvm-start") == OwnershipDecision.DENIED) {
                return;
            }

            // 2. Workload Reality Context
            activeLocalWorkloads.put(jobId, new LocalExecution(executionId, ExecutionKind.JVM, java.time.Instant.now(), false));
            jobsStarted.incrementAndGet();

            // 3. Metadata Publication
            // Since JVM execution is synchronous, we publish RUNNING immediately before the blocking execution.
            PublicationResult pub = publishState(jobId, executionId, JobState.RUNNING, null, null);
            if (pub == PublicationResult.ACKNOWLEDGED) {
                activeLocalWorkloads.computeIfPresent(jobId, (k, v) ->
                        new LocalExecution(v.executionId(), v.kind(), v.startedAt(), true));
                if (metricsRegistry != null) metricsRegistry.counter("aegis_running_acknowledged").increment();
            } else {
                if (metricsRegistry != null) metricsRegistry.counter("aegis_running_unknown").increment();
            }

            byte[] result;
            int memoryMb = (int) record.getSpec().getResources().getMemoryMb();
            String artifactId = record.getSpec().getCodeFileId();
            AtomicLong lastCheckpointFailureLogAt = new AtomicLong(0);
            
            // Phase 3, 4, 7 Checkpoint Listener
            java.util.function.Consumer<byte[]> checkpointListener = payload -> {
                long checkpointSequence = -1;
                try {
                    CheckpointEnvelope env = CheckpointEnvelope.fromByteArray(payload);
                    checkpointSequence = env.sequence();
                    if (env.executionId() != record.getExecutionId()) {
                        if (metricsRegistry != null) metricsRegistry.counter("aegis_execution_fencing_total").increment();
                        log.warn("Dropped stale checkpoint for job {} (env execution {} != current {})", jobId, env.executionId(), record.getExecutionId());
                        return;
                    }
                    
                    String checkpointPath = "/jobs/" + jobId + "/checkpoints/chk-" + env.sequence();
                    fileSystem.write(checkpointPath, payload);
                    
                    // Phase 3: aegis.checkpoint.retention limit cleanup
                    try {
                        int retentionLimit = 5; // default retention
                        java.util.List<com.aegisos.proto.FileMetadata> existingCheckpoints = fileSystem.list("/jobs/" + jobId + "/checkpoints/chk-");
                        if (existingCheckpoints.size() > retentionLimit) {
                            existingCheckpoints.sort(java.util.Comparator.comparingLong(com.aegisos.proto.FileMetadata::getCreatedAt));
                            for (int i = 0; i < existingCheckpoints.size() - retentionLimit; i++) {
                                fileSystem.delete(existingCheckpoints.get(i).getName());
                            }
                        }
                    } catch (Exception e) {
                        if (isTransientStorageFailure(e)) {
                            log.debug("Checkpoint retention for job {} deferred: {}", jobId, compactException(e));
                        } else {
                            log.warn("Failed to apply checkpoint retention for {}", jobId, e);
                        }
                    }
                    
                    com.aegisos.proto.CheckpointMetadata meta = com.aegisos.proto.CheckpointMetadata.newBuilder()
                            .setSequence(env.sequence())
                            .setArtifactId(artifactId != null ? artifactId : "")
                            .setTimestamp(System.currentTimeMillis())
                            .setSizeBytes(payload.length)
                            .build();
                            
                    com.aegisos.proto.UpdateJobCheckpoint cmdPayload = com.aegisos.proto.UpdateJobCheckpoint.newBuilder()
                            .setJobId(jobId)
                            .setExecutionId(env.executionId())
                            .setCheckpointFileId(checkpointPath)
                            .setMetadata(meta)
                            .build();
                            
                    com.aegisos.proto.StateCommand cmd = com.aegisos.proto.StateCommand.newBuilder()
                            .setType(com.aegisos.proto.CommandType.UPDATE_JOB_CHECKPOINT)
                            .setPayload(cmdPayload.toByteString())
                            .build();
                            
                    consensus.propose(cmd);
                    log.info("Saved checkpoint sequence {} for job {} ({} bytes)", env.sequence(), jobId, payload.length);
                } catch (Exception e) {
                    logCheckpointFailure(jobId, checkpointSequence, e, lastCheckpointFailureLogAt);
                }
            };

            java.util.Map<String, String> mountPaths = new java.util.HashMap<>();
            java.util.List<String> pinnedArtifacts = new java.util.ArrayList<>();
            try {
                for (com.aegisos.proto.ArtifactReference ref : record.getSpec().getArtifactsList()) {
                    com.aegisos.proto.ArtifactRecord art = null;
                    for (int i = 0; i < 50; i++) {
                        java.util.Optional<com.aegisos.proto.ArtifactRecord> opt = artifactRegistry.bySha256(ref.getSha256());
                        if (opt.isPresent()) {
                            art = opt.get();
                            break;
                        }
                        Thread.sleep(100);
                    }
                    if (art == null) {
                        throw new IllegalStateException("unknown artifact: " + ref.getSha256());
                    }
                    artifactClassLoader.getCache().pin(ref.getSha256());
                    pinnedArtifacts.add(ref.getSha256());
                    java.nio.file.Path localPath = artifactClassLoader.getCache().resolve(ref.getSha256(), art.getFsPath());
                    mountPaths.put(ref.getMountPath(), localPath.toAbsolutePath().toString());
                }

                if (!artifactId.isEmpty()) {
                    com.aegisos.proto.ArtifactRecord artifact = null;
                    for (int i = 0; i < 50; i++) {
                        java.util.Optional<com.aegisos.proto.ArtifactRecord> opt = artifactRegistry.bySha256(artifactId);
                        if (opt.isPresent()) {
                            artifact = opt.get();
                            break;
                        }
                        Thread.sleep(100);
                    }
                    if (artifact == null) {
                        throw new IllegalStateException("unknown artifact: " + artifactId);
                    }
                    artifactClassLoader.getCache().pin(artifactId);
                    pinnedArtifacts.add(artifactId);
                    String className = record.getSpec().getClassName();
                    String[] args = Serialization.deserialize(record.getSpec().getArgs().toByteArray());
                    result = jvmBackend.executeSyncFromArtifact(jobId, record.getExecutionId(), artifactId, artifact.getFsPath(),
                            className, args, restoreState, memoryMb, mountPaths, checkpointListener);
                } else {
                    result = jvmBackend.executeSync(jobId, record.getExecutionId(), record.getSpec().getArgs().toByteArray(), restoreState, memoryMb, mountPaths, checkpointListener);
                }
            } finally {
                for (String pinned : pinnedArtifacts) {
                    artifactClassLoader.getCache().unpin(pinned);
                }
            }

            // Gate A: check fencing before publishing state
            if (isSuperseded(jobId, executionId)) {
                fencingDrops.incrementAndGet();
                jobsSuperseded.incrementAndGet();
                if (metricsRegistry != null) metricsRegistry.counter("aegis_execution_fencing_total").increment();
                log.warn("Execution {} of job {} superseded, discarding result", executionId, jobId);
                return;
            }
            // Commit terminal state BEFORE non-essential work
            terminalScheduler.enqueue(jobId, executionId, JobState.COMPLETED, result, null);
            jobsCompleted.incrementAndGet();
            log.info("Job {} COMPLETED (queued for publication)", jobId);
            
            // Gate B: upload job logs to AegisFS (best effort)
            uploadJobLogsBestEffort(jobId, executionId);
        } catch (Exception e) {
            if (shuttingDown) {
                log.info("Job {} aborted due to node shutdown, ignoring failure so it can be marked LOST.", jobId);
            } else if (isCurrentState(jobId, executionId, JobState.CANCELLED)) {
                log.info("Execution {} of job {} exited after cancellation; preserving CANCELLED state", executionId, jobId);
            } else {
                if (!isSuperseded(jobId, executionId)) {
                    log.error("Job {} FAILED", jobId, e);
                    terminalScheduler.enqueue(jobId, executionId, JobState.FAILED, null,
                            e.getMessage() == null ? "error" : e.getMessage());
                    jobsFailed.incrementAndGet();
                    uploadJobLogsBestEffort(jobId, executionId);
                } else {
                    fencingDrops.incrementAndGet();
                    jobsSuperseded.incrementAndGet();
                    if (metricsRegistry != null) metricsRegistry.counter("aegis_execution_fencing_total").increment();
                    log.warn("Execution {} of job {} superseded (failed path), discarding", executionId, jobId);
                }
            }
        } finally {
            heartbeatTask.cancel(true);
            running.decrementAndGet();
            activeLocalWorkloads.remove(jobId);
            cleanupJobFiles(jobId, executionId);
        }
    }

    private PublicationResult publishState(String jobId, long executionId, JobState state, byte[] result, String error) {
        if (shuttingDown) {
            log.debug("Skipping {} publication for job {} execution {} during shutdown", state, jobId, executionId);
            return PublicationResult.UNKNOWN;
        }

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
            return PublicationResult.ACKNOWLEDGED;
        } catch (java.util.concurrent.TimeoutException e) {
            log.debug("Timeout publishing {} for job {} execution {}: {}", state, jobId, executionId, e.getMessage());
            return PublicationResult.UNKNOWN;
        } catch (Exception e) {
            log.debug("Failed publishing {} for job {} execution {}: {}", state, jobId, executionId, e.getMessage());
            return PublicationResult.UNKNOWN;
        }
    }

    private OwnershipDecision validateOwnershipBeforeSideEffect(String jobId, long executionId, String phase) {
        if (isSuperseded(jobId, executionId)) {
            fencingDrops.incrementAndGet(); // Also serves as our OWNERSHIP_DENIED proxy metric
            
            // Log authoritative execution IDs for debugging
            long registryExecId = registry.get(jobId).map(com.aegisos.proto.JobRecord::getExecutionId).orElse(-1L);
            log.warn("Workload Fence denied: job={} attempt={} registry={} phase={}", 
                    jobId, executionId, registryExecId, phase);
            
            return OwnershipDecision.DENIED;
        }
        if (registry.isTerminal(jobId)) {
            fencingDrops.incrementAndGet();
            log.warn("Workload Fence denied: job={} attempt={} phase={} (already terminal)", 
                    jobId, executionId, phase);
            return OwnershipDecision.DENIED;
        }
        return OwnershipDecision.GRANTED;
    }

    private boolean update(String jobId, long executionId, JobState state, byte[] result, String error) {
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

        if (shuttingDown) {
            log.debug("Skipping {} update for job {} execution {} during shutdown", state, jobId, executionId);
            return false;
        }
        if (isSuperseded(jobId, executionId)) {
            log.debug("Skipping {} update for superseded execution {} of job {}", state, executionId, jobId);
            return false;
        }

        try {
            consensus.propose(cmd).get(10, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            log.debug("Failed to commit {} for job {} execution {}: {}", state, jobId, executionId, e.getMessage());
            return false;
        }
    }

    /**
     * Uploads stdout/stderr from the worker's temporary directory to AegisFS.
     * Gate B: only uploads if this execution has not been superseded.
     * Best effort: does not throw exceptions.
     */
    private void uploadJobLogsBestEffort(String jobId, long executionId) {
        try {
            if (System.getProperty("aegis.test.delay_upload_logs") != null) {
                long delay = Long.parseLong(System.getProperty("aegis.test.delay_upload_logs"));
                log.debug("TEST HOOK: Delaying log upload for {}ms", delay);
                try { Thread.sleep(delay); } catch (InterruptedException e) {}
                log.debug("TEST HOOK: Finished log upload delay");
            }
            if (isSuperseded(jobId, executionId)) {
                fencingDrops.incrementAndGet();
                log.warn("Execution {} of job {} superseded, skipping log upload (Gate B)", executionId, jobId);
                return;
            }
            java.nio.file.Path execRoot = workspaceRoot.resolve(jobId).resolve("exec-" + executionId);
            WorkspaceInfo workspace = new WorkspaceInfo(execRoot);
            uploadLogFile(workspace.stdoutLog(), jobId, executionId, "stdout");
            uploadLogFile(workspace.stderrLog(), jobId, executionId, "stderr");
        } catch (Exception e) {
            log.error("Failed to upload logs for job {} execution {}", jobId, executionId, e);
        }
    }

    private void uploadLogFile(java.nio.file.Path localFile, String jobId, long executionId, String name) {
        try {
            boolean exists = java.nio.file.Files.exists(localFile);
            long size = exists ? java.nio.file.Files.size(localFile) : -1;
            log.debug("uploadLogFile: localFile={}, exists={}, size={}", localFile.toAbsolutePath(), exists, size);
            if (exists) {
                byte[] data = java.nio.file.Files.readAllBytes(localFile);
                if (data.length > 0) {
                    String fsPath = "/jobs/" + jobId + "/" + executionId + "/" + name;
                    fileSystem.write(fsPath, data);
                    logUploadsSucceeded.incrementAndGet();
                    log.debug("Uploaded job {} execution {} {} ({} bytes) -> {}",
                            jobId, executionId, name, data.length, fsPath);
                } else {
                    log.debug("uploadLogFile: localFile={} exists but has size 0", localFile.toAbsolutePath());
                }
            } else {
                log.debug("uploadLogFile: localFile={} does not exist", localFile.toAbsolutePath());
            }
        } catch (Exception e) {
            logUploadsFailed.incrementAndGet();
            if (isTransientStorageFailure(e)) {
                log.warn("Skipped {} log upload for job {} execution {} while storage is unavailable: {}",
                        name, jobId, executionId, compactException(e));
            } else {
                log.error("Failed to upload {} for job {}: {}", name, jobId, e.getMessage(), e);
            }
        }
    }

    private void logCheckpointFailure(String jobId, long checkpointSequence, Exception e, AtomicLong lastLogAt) {
        if (!isTransientStorageFailure(e)) {
            log.error("Failed to process checkpoint{} for job {}",
                    checkpointSequence >= 0 ? " sequence " + checkpointSequence : "", jobId, e);
            return;
        }

        long now = System.currentTimeMillis();
        long last = lastLogAt.get();
        if (now - last >= CHECKPOINT_FAILURE_LOG_INTERVAL_MS && lastLogAt.compareAndSet(last, now)) {
            log.warn("Checkpoint{} for job {} temporarily deferred: {}",
                    checkpointSequence >= 0 ? " sequence " + checkpointSequence : "", jobId, compactException(e));
        } else {
            log.debug("Checkpoint{} for job {} temporarily deferred",
                    checkpointSequence >= 0 ? " sequence " + checkpointSequence : "", jobId, e);
        }
    }

    private boolean isTransientStorageFailure(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            String className = t.getClass().getName();
            if (className.contains("NotLeaderException") || className.contains("RejectedExecutionException")) {
                return true;
            }
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("replication requirement not met")
                    || normalized.contains("partitioned from")
                    || normalized.contains("not connected to")
                    || normalized.contains("network closed")
                    || normalized.contains("no known leader")
                    || normalized.contains("raftnode is shutting down")
                    || normalized.contains("executor")) {
                return true;
            }
        }
        return false;
    }

    private String compactException(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getMessage();
        }
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }
        return root.getClass().getSimpleName() + ": " + message.replaceAll("\\s+", " ").trim();
    }

    private void cleanupJobFiles(String jobId, long executionId) {
        long delaySeconds = this.workspaceCleanupDelaySeconds;
        if (delaySeconds <= 0) {
            log.trace("Cleaning up workspace for job {} execution {} immediately", jobId, executionId);
            deleteWorkspace(jobId, executionId);
            return;
        }
        if (shuttingDown || cleanupExecutor.isShutdown()) {
            log.debug("Skipping deferred cleanup for job {} execution {} during shutdown", jobId, executionId);
            return;
        }
        try {
            cleanupExecutor.schedule(() -> {
                log.trace("Executing deferred workspace cleanup for job {} execution {}", jobId, executionId);
                deleteWorkspace(jobId, executionId);
            }, delaySeconds, TimeUnit.SECONDS);
            log.trace("Scheduled workspace cleanup for job {} execution {} in {} seconds", jobId, executionId, delaySeconds);
        } catch (RejectedExecutionException e) {
            log.debug("Cleanup executor stopped while scheduling job {} execution {}; cleanup skipped",
                    jobId, executionId);
        }
    }

    private void deleteWorkspace(String jobId, long executionId) {
        java.nio.file.Path execRoot = workspaceRoot.resolve(jobId).resolve("exec-" + executionId);
        try {
            if (Files.exists(execRoot)) {
                try (java.util.stream.Stream<java.nio.file.Path> stream = Files.walk(execRoot)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                          .forEach(p -> {
                              try {
                                  Files.delete(p);
                              } catch (IOException e) {
                                  log.warn("Failed to delete {}, reason: {}", p, e.getMessage());
                              }
                          });
                }
                log.trace("Cleaned up workspace for job {} execution {}", jobId, executionId);
            }
        } catch (Exception e) {
            log.warn("Failed to clean up workspace for job {} execution {}", jobId, executionId, e);
        }
    }

    public void close() {
        log.debug("ProcessRuntimeAgent shutting down: runningJobs={}", running.get());
        shuttingDown = true;
        // Cancel all active jobs to ensure child processes are killed
        jvmBackend.close();
        try {
            if (containerEngine instanceof com.aegisos.runtime.container.DockerEngine) {
                // Not closing standard container engine
            } else if (containerEngine instanceof com.aegisos.runtime.container.MockContainerEngine) {
                // Cleanup mock if needed
            }
        } catch (Exception e) {
            log.warn("Error closing container engine", e);
        }
        heartbeatScheduler.shutdownNow();
        cleanupExecutor.shutdownNow();
        try {
            cleanupExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.debug("ProcessRuntimeAgent shutdown complete: cleanupExecutor.isTerminated={}", cleanupExecutor.isTerminated());
    }
}

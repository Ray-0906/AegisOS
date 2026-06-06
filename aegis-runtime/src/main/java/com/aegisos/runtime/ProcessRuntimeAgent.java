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
    private final ArtifactRegistry artifactRegistry;
    private final ArtifactClassLoader artifactClassLoader;
    private final JobRegistry registry = new JobRegistry();
    private final AtomicInteger running = new AtomicInteger(0);
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
    }

    public void registerAppliers() {
        registry.registerWith(consensus.stateMachine());
    }

    public void start() {
        // RUN_JOB is delivered via the network handler installed below.
        network.registerHandler(MessageType.CANCEL_JOB, msg -> {
            try {
                String jobId = new String(msg.payload(), java.nio.charset.StandardCharsets.UTF_8);
                cancelJobLocal(jobId);
            } catch (Exception e) {
                log.error("Failed to process CANCEL_JOB", e);
            }
            return null;
        });
        log.info("Process runtime agent started");
    }

    public AegisMessage onRunJob(AegisMessage msg) {
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
        Thread.ofVirtual().name("aegis-job-" + record.getSpec().getJobId())
                .start(() -> executeJob(record));
    }
    
    public void cancelJob(String jobId) {
        registry.get(jobId).ifPresentOrElse(job -> {
            NodeId assignedNode = NodeId.of(job.getAssignedNodeId().toByteArray());
            if (self.equals(assignedNode)) {
                cancelJobLocal(jobId);
            } else {
                log.info("Forwarding cancel for job {} to node {}", jobId, assignedNode.shortId());
                network.sendAsync(assignedNode, MessageType.CANCEL_JOB, jobId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }, () -> {
            log.warn("Cannot cancel unknown job {}", jobId);
            cancelJobLocal(jobId); // Try locally anyway just in case
        });
    }

    private void cancelJobLocal(String jobId) {
        log.info("Canceling job {} locally", jobId);
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
        }, 0, 10, java.util.concurrent.TimeUnit.SECONDS);

        try {
            update(jobId, executionId, JobState.RUNNING, null, null);

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

            update(jobId, executionId, JobState.COMPLETED, result, null);
            log.info("Job {} COMPLETED", jobId);
        } catch (Exception e) {
            if (shuttingDown) {
                log.info("Job {} aborted due to node shutdown, ignoring failure so it can be marked LOST.", jobId);
            } else {
                log.error("Job {} FAILED", jobId, e);
                update(jobId, executionId, JobState.FAILED, null, e.getMessage() == null ? "error" : e.getMessage());
            }
        } finally {
            hbScheduler.shutdownNow();
            running.decrementAndGet();
        }
    }

    private void update(String jobId, long executionId, JobState state, byte[] result, String error) {
        JobUpdate.Builder b = JobUpdate.newBuilder().setJobId(jobId).setExecutionId(executionId).setState(state);
        if (result != null) {
            b.setResult(ByteString.copyFrom(result));
        }
        if (error != null) {
            b.setError(error);
        }
        try {
            consensus.propose(StateCommand.newBuilder()
                    .setType(CommandType.UPDATE_JOB)
                    .setPayload(b.build().toByteString())
                    .build()).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("failed to record job update for {}: {}", jobId, e.toString());
        }
    }

    public void close() {
        log.info("ProcessRuntimeAgent shutting down");
        shuttingDown = true;
        // Cancel all active jobs to ensure child processes are killed
        executor.close();
    }
}

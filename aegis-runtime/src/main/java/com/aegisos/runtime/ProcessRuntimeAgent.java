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
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private final NodeId self;
    private final JobExecutor executor;
    private final ArtifactRegistry artifactRegistry;
    private final ArtifactClassLoader artifactClassLoader;
    private final JobRegistry registry = new JobRegistry();
    private final AtomicInteger running = new AtomicInteger(0);
    private CheckpointManager checkpointManager; // set by Phase 6 wiring

    public ProcessRuntimeAgent(ConsensusModule consensus, NodeId self, AegisFS fileSystem,
                               ArtifactRegistry artifactRegistry, ArtifactClassLoader artifactClassLoader) {
        this.consensus = consensus;
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
        log.info("Process runtime agent started");
    }

    public AegisMessage onRunJob(AegisMessage msg) {
        try {
            RunJob runJob = RunJob.parseFrom(msg.payload());
            dispatchLocal(runJob.getRecord());
        } catch (Exception e) {
            log.warn("bad RUN_JOB: {}", e.toString());
        }
        return null;
    }

    /** Starts executing an assigned job on a virtual thread (also used for self-assignment). */
    public void dispatchLocal(JobRecord record) {
        Thread.ofVirtual().name("aegis-job-" + record.getSpec().getJobId())
                .start(() -> executeJob(record));
    }

    public void setCheckpointManager(CheckpointManager checkpointManager) {
        this.checkpointManager = checkpointManager;
    }

    public JobRegistry registry() {
        return registry;
    }

    public int runningJobs() {
        return running.get();
    }

    public boolean canAccept() {
        return running.get() < MAX_CONCURRENT_JOBS;
    }

    private void executeJob(JobRecord record) {
        String jobId = record.getSpec().getJobId();
        running.incrementAndGet();
        try {
            update(jobId, JobState.RUNNING, null, null);

            byte[] restoreState = null;
            if (checkpointManager != null && !record.getCheckpointFileId().isEmpty()) {
                restoreState = checkpointManager.loadCheckpoint(record.getCheckpointFileId());
            }

            byte[] result;
            String artifactId = record.getSpec().getCodeFileId();
            if (!artifactId.isEmpty()) {
                com.aegisos.proto.ArtifactRecord artifact = artifactRegistry.bySha256(artifactId)
                        .orElseThrow(() -> new IllegalStateException("unknown artifact: " + artifactId));
                String className = record.getSpec().getClassName();
                String[] args = Serialization.deserialize(record.getSpec().getArgs().toByteArray());
                
                if (checkpointManager != null) {
                    result = checkpointManager.runArtifactWithCheckpointing(jobId, artifactId,
                            artifact.getFsPath(), className, args, restoreState, artifactClassLoader);
                } else {
                    result = executor.runFromArtifact(jobId, artifactId, artifact.getFsPath(),
                            className, args, restoreState);
                }
            } else {
                if (checkpointManager != null) {
                    result = checkpointManager.runWithCheckpointing(jobId,
                            record.getSpec().getArgs().toByteArray(), restoreState, executor);
                } else {
                    result = executor.run(jobId, record.getSpec().getArgs().toByteArray(), restoreState);
                }
            }

            update(jobId, JobState.COMPLETED, result, null);
            log.info("Job {} COMPLETED", jobId);
        } catch (Exception e) {
            log.warn("Job {} FAILED: {}", jobId, e.toString());
            update(jobId, JobState.FAILED, null, e.getMessage() == null ? "error" : e.getMessage());
        } finally {
            running.decrementAndGet();
        }
    }

    private void update(String jobId, JobState state, byte[] result, String error) {
        JobUpdate.Builder b = JobUpdate.newBuilder().setJobId(jobId).setState(state);
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
}

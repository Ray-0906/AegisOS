package com.aegisos.runtime;

import com.aegisos.consensus.SnapshotException;
import com.aegisos.consensus.SnapshotParticipant;
import com.aegisos.consensus.ClusterStateMachine;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import com.aegisos.proto.JobUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Replicated view of all jobs, built by applying committed ASSIGN_JOB and UPDATE_JOB
 * commands. Lets any node observe job state and results.
 */
public final class JobRegistry implements SnapshotParticipant {

    private static final Logger log = LoggerFactory.getLogger(JobRegistry.class);

    /** Valid state transitions for the job lifecycle state machine. */
    private static final Map<JobState, Set<JobState>> VALID_TRANSITIONS;
    static {
        Map<JobState, Set<JobState>> m = new EnumMap<>(JobState.class);
        m.put(JobState.PENDING,   EnumSet.of(JobState.QUEUED));
        m.put(JobState.QUEUED,    EnumSet.of(JobState.RESTORING, JobState.RUNNING, JobState.FAILED, JobState.LOST));
        m.put(JobState.RESTORING, EnumSet.of(JobState.RUNNING, JobState.FAILED, JobState.LOST));
        m.put(JobState.RUNNING,   EnumSet.of(JobState.COMPLETED, JobState.FAILED, JobState.CANCELLED, JobState.LOST));
        m.put(JobState.LOST,      EnumSet.of(JobState.QUEUED));
        VALID_TRANSITIONS = Map.copyOf(m);
    }

    /**
     * Returns {@code true} if the transition from {@code from} to {@code to} is
     * permitted by the job lifecycle state machine.
     * <p>Transitions from {@link JobState#JOB_UNKNOWN} (proto default / initial state)
     * are always allowed, as are idempotent self-transitions.</p>
     */
    static boolean isValidTransition(JobState from, JobState to) {
        if (from == to) return true;
        if (from == JobState.JOB_UNKNOWN) return true;
        Set<JobState> allowed = VALID_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    private final ConcurrentHashMap<String, JobRecord> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JobCheckpointRecord> checkpoints = new ConcurrentHashMap<>();
    private final AtomicInteger invalidTransitionCount = new AtomicInteger();
    private final com.aegisos.core.observability.MetricsRegistry metricsRegistry;
    private final com.aegisos.core.observability.TimelineRegistry timelineRegistry;

    public JobRegistry() {
        this.metricsRegistry = null;
        this.timelineRegistry = null;
    }

    public JobRegistry(com.aegisos.core.observability.MetricsRegistry metricsRegistry,
                       com.aegisos.core.observability.TimelineRegistry timelineRegistry) {
        this.metricsRegistry = metricsRegistry;
        this.timelineRegistry = timelineRegistry;
    }

    /** Returns the number of invalid state transitions observed (for test assertions). */
    public int invalidTransitionCount() {
        return invalidTransitionCount.get();
    }

    public Optional<JobCheckpointRecord> getCheckpoint(String jobId) {
        return Optional.ofNullable(checkpoints.get(jobId));
    }

    public void registerWith(ClusterStateMachine stateMachine) {
        stateMachine.register(CommandType.ASSIGN_JOB, (index, cmd) -> {
            try {
                JobRecord record = JobRecord.parseFrom(cmd.getPayload());
                jobs.compute(record.getSpec().getJobId(), (id, existing) -> {
                    if (existing != null && existing.getExecutionId() >= record.getExecutionId()) {
                        log.warn("Fencing rejected ASSIGN_JOB for {}: current execId {}, new execId {}",
                                id, existing.getExecutionId(), record.getExecutionId());
                        return existing;
                    }
                    return record;
                });
            } catch (Exception e) {
                log.warn("bad ASSIGN_JOB at {}", index);
            }
        });
        stateMachine.register(CommandType.SUBMIT_JOB, (index, cmd) -> {
            try {
                JobRecord record = JobRecord.parseFrom(cmd.getPayload());
                if (jobs.putIfAbsent(record.getSpec().getJobId(), record) == null) {
                    updateGauge(JobState.JOB_UNKNOWN, record.getState(), record);
                    if (timelineRegistry != null) {
                        timelineRegistry.recordEvent(record.getSpec().getJobId(), com.aegisos.core.observability.JobEventType.SUBMITTED, null, "");
                    }
                }
            } catch (Exception e) {
                log.warn("bad SUBMIT_JOB at {}", index);
            }
        });
        stateMachine.register(CommandType.UPDATE_JOB, (index, cmd) -> {
            try {
                applyUpdate(JobUpdate.parseFrom(cmd.getPayload()));
            } catch (Exception e) {
                log.warn("bad UPDATE_JOB at {}", index);
            }
        });
        stateMachine.register(CommandType.UPDATE_JOB_CHECKPOINT, (index, cmd) -> {
            try {
                com.aegisos.proto.UpdateJobCheckpoint update = com.aegisos.proto.UpdateJobCheckpoint.parseFrom(cmd.getPayload());
                String jobId = update.getJobId();
                JobRecord existing = jobs.get(jobId);
                if (existing != null && existing.getExecutionId() != update.getExecutionId()) {
                    log.warn("Fencing rejected UPDATE_JOB_CHECKPOINT for {}: current execId {}, update execId {}",
                            jobId, existing.getExecutionId(), update.getExecutionId());
                    return;
                }
                JobCheckpointRecord currentCheckpoint = checkpoints.get(jobId);
                if (currentCheckpoint != null
                        && currentCheckpoint.executionId() == update.getExecutionId()
                        && currentCheckpoint.metadata().getSequence() > update.getMetadata().getSequence()) {
                    log.warn("Ignoring stale checkpoint update for {}: current seq {}, update seq {}",
                            jobId, currentCheckpoint.metadata().getSequence(), update.getMetadata().getSequence());
                    return;
                }
                checkpoints.put(jobId, new JobCheckpointRecord(
                        jobId, update.getExecutionId(), update.getCheckpointFileId(), update.getMetadata()
                ));
                // Update the JobRecord's checkpoint file ID too for legacy compatibility
                if (existing != null) {
                    jobs.put(jobId, existing.toBuilder().setCheckpointFileId(update.getCheckpointFileId()).build());
                }
                log.info("Registered checkpoint {} for job {}", update.getMetadata().getSequence(), jobId);
            } catch (Exception e) {
                log.warn("bad UPDATE_JOB_CHECKPOINT at {}", index);
            }
        });
    }

    private void applyUpdate(JobUpdate update) {
        jobs.compute(update.getJobId(), (id, existing) -> {
            if (existing != null && existing.getExecutionId() != update.getExecutionId()) {
                log.warn("Fencing rejected update for job {}: current execId {}, update execId {}",
                        id, existing.getExecutionId(), update.getExecutionId());
                return existing;
            }

            JobRecord.Builder b = existing == null ? JobRecord.newBuilder() : existing.toBuilder();
            JobState oldState = existing == null ? JobState.JOB_UNKNOWN : existing.getState();
            JobState newState = update.getState();
            if (!isValidTransition(oldState, newState)) {
                invalidTransitionCount.incrementAndGet();
                if (isTerminalState(oldState) && oldState != newState) {
                    log.warn("Ignoring invalid terminal job state transition for {}: {} -> {}",
                            id, oldState, newState);
                    return existing;
                }
                log.warn("Invalid job state transition for {}: {} -> {} (applying anyway - entry is Raft-committed)",
                        id, oldState, newState);
            }

            b.setState(newState);
            log.info("JobRegistry update for {}: {} -> {}", id, oldState, newState);
            if (!update.getCheckpointFileId().isEmpty()) {
                b.setCheckpointFileId(update.getCheckpointFileId());
            }
            if (!update.getResult().isEmpty()) {
                b.setResult(update.getResult());
            }
            if (!update.getError().isEmpty()) {
                b.setError(update.getError());
            }
            if (oldState != newState && isValidTransition(oldState, newState)) {
                updateGauge(oldState, newState, b.build());
                emitEvent(oldState, newState, b.build(), update);
            }

            if (isTerminalState(newState)) {
                b.setCompletedAt(System.currentTimeMillis());
            }
            return b.build();
        });
    }

    private void updateGauge(JobState oldState, JobState newState, JobRecord record) {
        if (metricsRegistry == null) return;
        if (oldState == JobState.QUEUED) metricsRegistry.gauge("aegis_jobs_queued").decrement();
        if (newState == JobState.QUEUED) metricsRegistry.gauge("aegis_jobs_queued").increment();
        
        if (oldState == JobState.RUNNING) {
            metricsRegistry.gauge("aegis_jobs_running").decrement();
            if (record.getSpec().getRuntime() == com.aegisos.proto.RuntimeType.RUNTIME_CONTAINER) {
                metricsRegistry.gauge("aegis_runtime_container_jobs_active").decrement();
            } else {
                metricsRegistry.gauge("aegis_runtime_jvm_jobs_active").decrement();
            }
        }
        if (newState == JobState.RUNNING) {
            metricsRegistry.gauge("aegis_jobs_running").increment();
            if (record.getSpec().getRuntime() == com.aegisos.proto.RuntimeType.RUNTIME_CONTAINER) {
                metricsRegistry.gauge("aegis_runtime_container_jobs_active").increment();
            } else {
                metricsRegistry.gauge("aegis_runtime_jvm_jobs_active").increment();
            }
        }
        if (newState == JobState.COMPLETED) {
            metricsRegistry.counter("aegis_jobs_completed_total").increment();
        }
        if (newState == JobState.FAILED) {
            metricsRegistry.counter("aegis_jobs_failed_total").increment();
        }
    }

    private void emitEvent(JobState oldState, JobState newState, JobRecord record, JobUpdate update) {
        if (timelineRegistry == null) return;
        com.aegisos.core.observability.JobEventType type = null;
        String details = update != null && !update.getError().isEmpty() ? update.getError() : "";

        if (newState == JobState.QUEUED) {
            type = com.aegisos.core.observability.JobEventType.QUEUED;
        } else if (newState == JobState.RUNNING) {
            if (oldState == JobState.QUEUED) {
                type = com.aegisos.core.observability.JobEventType.ASSIGNED;
            } else {
                type = com.aegisos.core.observability.JobEventType.STARTED;
            }
        } else if (newState == JobState.COMPLETED) {
            type = com.aegisos.core.observability.JobEventType.COMPLETED;
        } else if (newState == JobState.FAILED) {
            type = com.aegisos.core.observability.JobEventType.FAILED;
        } else if (newState == JobState.LOST) {
            type = com.aegisos.core.observability.JobEventType.HEARTBEAT_LOST;
        } else if (newState == JobState.CANCELLED) {
            type = com.aegisos.core.observability.JobEventType.FENCED; // Or cancelled, mapped to fenced if we only have fenced
        }

        if (type != null) {
            String nodeId = record.getAssignedNodeId().isEmpty() ? null : record.getAssignedNodeId().toStringUtf8();
            timelineRegistry.recordEvent(record.getSpec().getJobId(), type, nodeId, details);
        }
    }

    public Optional<JobRecord> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public boolean isTerminal(String jobId) {
        JobRecord r = jobs.get(jobId);
        return r != null && isTerminalState(r.getState());
    }

    private static boolean isTerminalState(JobState state) {
        return state == JobState.COMPLETED || state == JobState.FAILED || state == JobState.CANCELLED;
    }

    public Collection<JobRecord> all() {
        return new ArrayList<>(jobs.values());
    }

    /** Returns jobs not in a terminal state (COMPLETED/FAILED/CANCELLED). Used for ResourceAllocator re-hydration. */
    public List<JobRecord> activeJobs() {
        return jobs.values().stream()
                .filter(r -> r.getState() != JobState.COMPLETED
                        && r.getState() != JobState.FAILED
                        && r.getState() != JobState.CANCELLED)
                .collect(Collectors.toList());
    }

    // --- SnapshotParticipant ---

    @Override public String id() { return "job-registry"; }

    @Override
    public synchronized byte[] snapshot() throws SnapshotException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            var values = new ArrayList<>(jobs.values());
            out.writeInt(values.size());
            for (JobRecord r : values) {
                byte[] bytes = r.toByteArray();
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            
            var ckptValues = new ArrayList<>(checkpoints.values());
            out.writeInt(ckptValues.size());
            for (JobCheckpointRecord r : ckptValues) {
                out.writeUTF(r.jobId());
                out.writeLong(r.executionId());
                out.writeUTF(r.checkpointFileId());
                byte[] bytes = r.metadata().toByteArray();
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new SnapshotException("Failed to snapshot JobRegistry", e);
        }
    }

    @Override
    public synchronized void restore(byte[] data) throws SnapshotException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            jobs.clear();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int len = in.readInt();
                byte[] buf = new byte[len];
                in.readFully(buf);
                JobRecord r = JobRecord.parseFrom(buf);
                jobs.put(r.getSpec().getJobId(), r);
            }
            
            checkpoints.clear();
            if (in.available() > 0) {
                int ckptCount = in.readInt();
                for (int i = 0; i < ckptCount; i++) {
                    String jobId = in.readUTF();
                    long executionId = in.readLong();
                    String fileId = in.readUTF();
                    int len = in.readInt();
                    byte[] buf = new byte[len];
                    in.readFully(buf);
                    com.aegisos.proto.CheckpointMetadata metadata = com.aegisos.proto.CheckpointMetadata.parseFrom(buf);
                    checkpoints.put(jobId, new JobCheckpointRecord(jobId, executionId, fileId, metadata));
                }
            }
            log.info("Restored JobRegistry: {} jobs", jobs.size());
        } catch (IOException e) {
            throw new SnapshotException("Failed to restore JobRegistry", e);
        }
    }
}

package com.aegisos.runtime;

import com.aegisos.consensus.ClusterStateMachine;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import com.aegisos.proto.JobUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replicated view of all jobs, built by applying committed ASSIGN_JOB and UPDATE_JOB
 * commands. Lets any node observe job state and results.
 */
public final class JobRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobRegistry.class);

    private final ConcurrentHashMap<String, JobRecord> jobs = new ConcurrentHashMap<>();

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
                jobs.putIfAbsent(record.getSpec().getJobId(), record);
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
    }

    private void applyUpdate(JobUpdate update) {
        jobs.compute(update.getJobId(), (id, existing) -> {
            if (existing != null && existing.getExecutionId() != update.getExecutionId()) {
                log.warn("Fencing rejected update for job {}: current execId {}, update execId {}",
                        id, existing.getExecutionId(), update.getExecutionId());
                return existing;
            }
            JobRecord.Builder b = existing == null ? JobRecord.newBuilder() : existing.toBuilder();
            JobState oldState = existing == null ? null : existing.getState();
            b.setState(update.getState());
            log.info("JobRegistry update for {}: {} -> {}", id, oldState, update.getState());
            if (!update.getCheckpointFileId().isEmpty()) {
                b.setCheckpointFileId(update.getCheckpointFileId());
            }
            if (!update.getResult().isEmpty()) {
                b.setResult(update.getResult());
            }
            if (!update.getError().isEmpty()) {
                b.setError(update.getError());
            }
            if (update.getState() == JobState.COMPLETED || update.getState() == JobState.FAILED) {
                b.setCompletedAt(System.currentTimeMillis());
            }
            return b.build();
        });
    }

    public Optional<JobRecord> get(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public boolean isTerminal(String jobId) {
        JobRecord r = jobs.get(jobId);
        return r != null && (r.getState() == JobState.COMPLETED || r.getState() == JobState.FAILED);
    }

    public Collection<JobRecord> all() {
        return new ArrayList<>(jobs.values());
    }
}

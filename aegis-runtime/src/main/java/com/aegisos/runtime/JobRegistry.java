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
                jobs.put(record.getSpec().getJobId(), record);
            } catch (Exception e) {
                log.warn("bad ASSIGN_JOB at {}", index);
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
            JobRecord.Builder b = existing == null ? JobRecord.newBuilder() : existing.toBuilder();
            b.setState(update.getState());
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

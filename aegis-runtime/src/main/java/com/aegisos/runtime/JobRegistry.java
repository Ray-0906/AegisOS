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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Replicated view of all jobs, built by applying committed ASSIGN_JOB and UPDATE_JOB
 * commands. Lets any node observe job state and results.
 */
public final class JobRegistry implements SnapshotParticipant {

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

    /** Returns jobs not in a terminal state (COMPLETED/FAILED). Used for ResourceAllocator re-hydration. */
    public List<JobRecord> activeJobs() {
        return jobs.values().stream()
                .filter(r -> r.getState() != JobState.COMPLETED && r.getState() != JobState.FAILED)
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
            log.info("Restored JobRegistry: {} jobs", jobs.size());
        } catch (IOException e) {
            throw new SnapshotException("Failed to restore JobRegistry", e);
        }
    }
}

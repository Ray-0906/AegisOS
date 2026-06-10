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
        m.put(JobState.QUEUED,    EnumSet.of(JobState.RUNNING, JobState.LOST));
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
    private final AtomicInteger invalidTransitionCount = new AtomicInteger();

    /** Returns the number of invalid state transitions observed (for test assertions). */
    public int invalidTransitionCount() {
        return invalidTransitionCount.get();
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
            JobState oldState = existing == null ? JobState.JOB_UNKNOWN : existing.getState();
            if (!isValidTransition(oldState, update.getState())) {
                log.warn("Invalid job state transition for {}: {} -> {} (applying anyway – entry is Raft-committed)",
                        id, oldState, update.getState());
                invalidTransitionCount.incrementAndGet();
            }
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
            if (update.getState() == JobState.COMPLETED || update.getState() == JobState.FAILED
                    || update.getState() == JobState.CANCELLED) {
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
        return r != null && (r.getState() == JobState.COMPLETED
                || r.getState() == JobState.FAILED
                || r.getState() == JobState.CANCELLED);
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

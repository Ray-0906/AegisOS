package com.aegisos.api;

import com.aegisos.consensus.NotLeaderException;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.message.MessageType;
import com.aegisos.network.NetworkLayer;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobSpec;
import com.aegisos.proto.JobState;
import com.aegisos.proto.RunJob;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.ProcessRuntimeAgent;
import com.aegisos.runtime.Serialization;
import com.aegisos.scheduler.Scheduler;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Optional;
import java.util.UUID;

/**
 * Public process-management API (design section 3.8): submit jobs, query status, await
 * results.
 */
public final class ProcessManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessManager.class);
    private static final long SCHEDULE_RETRY_WINDOW_MS = 15_000;
    private static final long SCHEDULE_RETRY_SLEEP_MS = 100;

    private final NetworkLayer network;
    private final Scheduler scheduler;
    private final ProcessRuntimeAgent agent;
    private final NodeId self;

    public ProcessManager(NetworkLayer network, Scheduler scheduler, ProcessRuntimeAgent agent, NodeId self) {
        this.network = network;
        this.scheduler = scheduler;
        this.agent = agent;
        this.self = self;
    }

    /** Submits a job: schedules it on the best-fit node and dispatches it for execution. */
    public JobHandle submit(AegisJob<?> job, int cpuCores, long memoryMb) throws Exception {
        String jobId = UUID.randomUUID().toString();
        
        com.aegisos.proto.ResourceRequest req = com.aegisos.proto.ResourceRequest.newBuilder()
                .setCpuCores(cpuCores)
                .setMemoryMb(memoryMb)
                .build();
                
        JobSpec spec = JobSpec.newBuilder()
                .setJobId(jobId)
                .setClassName(job.getClass().getName())
                .setArgs(ByteString.copyFrom(Serialization.serialize(job)))
                .setOwnerNodeId(ByteString.copyFrom(self.toBytes()))
                .setResources(req)
                .build();

        long pending = agent.registry().all().stream().filter(r -> r.getState() == JobState.PENDING).count();
        if (pending >= 1000) {
            throw new IllegalStateException("Admission queue full");
        }

        JobRecord record = JobRecord.newBuilder()
                .setSpec(spec)
                .setState(JobState.PENDING)
                .setExecutionId(0)
                .build();

        agent.consensus().propose(com.aegisos.proto.StateCommand.newBuilder()
                .setType(com.aegisos.proto.CommandType.SUBMIT_JOB)
                .setPayload(record.toByteString())
                .build()).get(10, java.util.concurrent.TimeUnit.SECONDS);

        log.info("Submitted job {} (PENDING)", jobId);
        return new JobHandle(jobId);
    }

    /** Submits an artifact-based job: worker will download artifact, load class, and execute. */
    public JobHandle submitArtifact(String artifactId, String className, java.util.List<String> args, int cpuCores, long memoryMb) throws Exception {
        String jobId = UUID.randomUUID().toString();
        byte[] argsBytes = Serialization.serialize((Serializable) args.toArray(new String[0]));

        com.aegisos.proto.ResourceRequest req = com.aegisos.proto.ResourceRequest.newBuilder()
                .setCpuCores(cpuCores)
                .setMemoryMb(memoryMb)
                .build();

        JobSpec spec = JobSpec.newBuilder()
                .setJobId(jobId)
                .setClassName(className)
                .setArgs(ByteString.copyFrom(argsBytes))
                .setCodeFileId(artifactId)
                .setOwnerNodeId(ByteString.copyFrom(self.toBytes()))
                .setResources(req)
                .build();

        long pending = agent.registry().all().stream().filter(r -> r.getState() == JobState.PENDING).count();
        if (pending >= 1000) {
            throw new IllegalStateException("Admission queue full");
        }

        JobRecord record = JobRecord.newBuilder()
                .setSpec(spec)
                .setState(JobState.PENDING)
                .setExecutionId(0)
                .build();

        agent.consensus().propose(com.aegisos.proto.StateCommand.newBuilder()
                .setType(com.aegisos.proto.CommandType.SUBMIT_JOB)
                .setPayload(record.toByteString())
                .build()).get(10, java.util.concurrent.TimeUnit.SECONDS);

        log.info("Submitted artifact job {} (artifact: {}) (PENDING)", jobId, artifactId);
        return new JobHandle(jobId);
    }

    private NodeId scheduleWithRetry(JobSpec spec) throws Exception {
        long deadline = System.currentTimeMillis() + SCHEDULE_RETRY_WINDOW_MS;
        while (true) {
            Optional<JobRecord> existing = agent.registry().get(spec.getJobId());
            if (existing.isPresent()) {
                return NodeId.of(existing.get().getAssignedNodeId().toByteArray());
            }

            try {
                return scheduler.schedule(spec, 1L);
            } catch (Exception e) {
                if (isNotLeaderException(e)) {
                    // #region agent log
                    dbg("H1", "ProcessManager.java:scheduleWithRetry", "caught NotLeaderException (possibly wrapped)", e, e.getCause());
                    // #endregion
                    if (System.currentTimeMillis() >= deadline) {
                        throw e;
                    }
                    Thread.sleep(SCHEDULE_RETRY_SLEEP_MS);
                } else {
                    // #region agent log
                    dbg("H1", "ProcessManager.java:scheduleWithRetry", "caught OTHER exception (not retried)", e, e.getCause());
                    // #endregion
                    throw e;
                }
            }
        }
    }

    private boolean isNotLeaderException(Throwable t) {
        while (t != null) {
            if (t instanceof NotLeaderException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    // #region agent log
    private static void dbg(String hyp, String loc, String msg, Throwable t, Throwable cause) {
        try {
            String line = "{\"sessionId\":\"e9aa02\",\"hypothesisId\":\"" + hyp + "\",\"location\":\"" + loc
                    + "\",\"message\":\"" + msg + "\",\"data\":{\"ex\":\"" + (t == null ? "null" : t.getClass().getName())
                    + "\",\"cause\":\"" + (cause == null ? "null" : cause.getClass().getName())
                    + "\"},\"timestamp\":" + System.currentTimeMillis() + "}\n";
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("C:\\Users\\astra\\Desktop\\projects\\AgeisOS\\debug-e9aa02.log"),
                    line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
    // #endregion

    public JobState status(String jobId) {
        return agent.registry().get(jobId).map(JobRecord::getState).orElse(JobState.JOB_UNKNOWN);
    }

    /** Blocks until the job reaches a terminal state, returning its serialized result. */
    public byte[] await(JobHandle handle, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastLog = 0;
        while (System.currentTimeMillis() < deadline) {
            Optional<JobRecord> record = agent.registry().get(handle.jobId());
            long now = System.currentTimeMillis();
            if (now - lastLog > 2000) {
                log.info("ProcessManager.await: Job {} status is {}", handle.jobId(), 
                         record.map(r -> r.getState().toString()).orElse("NOT_FOUND"));
                lastLog = now;
            }
            if (record.isPresent()) {
                JobRecord r = record.get();
                if (r.getState() == JobState.COMPLETED) {
                    return r.getResult().toByteArray();
                }
                if (r.getState() == JobState.FAILED) {
                    throw new RuntimeException("job failed: " + r.getError());
                }
            }
            Thread.sleep(50);
        }
        throw new java.util.concurrent.TimeoutException("job " + handle.jobId() + " did not finish in time");
    }

    /** Convenience: await and deserialize the typed result. */
    public <T> T awaitResult(JobHandle handle, long timeoutMs) throws Exception {
        return Serialization.deserialize(await(handle, timeoutMs));
    }
}

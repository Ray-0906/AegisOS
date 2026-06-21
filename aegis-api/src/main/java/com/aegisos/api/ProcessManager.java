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
import com.aegisos.core.crypto.Hashing;
import com.aegisos.core.util.HexUtil;
import com.aegisos.proto.ArtifactRecord;
import com.aegisos.fs.AegisFS;
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
    private final AegisFS fileSystem;

    public ProcessManager(NetworkLayer network, Scheduler scheduler, ProcessRuntimeAgent agent, NodeId self, AegisFS fileSystem) {
        this.network = network;
        this.scheduler = scheduler;
        this.agent = agent;
        this.self = self;
        this.fileSystem = fileSystem;
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

    /** Submits a raw JobSpec (used internally and for tests). */
    public JobHandle submitJob(JobSpec spec) throws Exception {
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

        log.info("Submitted raw job {} (PENDING)", spec.getJobId());
        return new JobHandle(spec.getJobId());
    }

    private NodeId scheduleWithRetry(JobSpec spec) throws Exception {
        long deadline = System.currentTimeMillis() + SCHEDULE_RETRY_WINDOW_MS;
        while (true) {
            Optional<JobRecord> existing = agent.registry().get(spec.getJobId());
            if (existing.isPresent()) {
                return NodeId.of(existing.get().getAssignedNodeId().toByteArray());
            }

            try {
                return scheduler.schedule(spec, 1L, "");
            } catch (Exception e) {
                if (isNotLeaderException(e)) {
                    if (System.currentTimeMillis() >= deadline) {
                        throw e;
                    }
                    Thread.sleep(SCHEDULE_RETRY_SLEEP_MS);
                } else {
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
                if (r.getState() == JobState.CANCELLED) {
                    throw new RuntimeException("job cancelled: " + r.getError());
                }
            }
            Thread.sleep(50);
        }
        Optional<JobRecord> finalRecord = agent.registry().get(handle.jobId());
        String finalState = finalRecord.isPresent() ? finalRecord.get().getState().toString() : "MISSING";
        System.out.println("AWAIT_RESULT_STATE=" + finalState);
        throw new java.util.concurrent.TimeoutException("job " + handle.jobId() + " did not finish in time");
    }

    /** Convenience: await and deserialize the typed result. */
    public <T> T awaitResult(JobHandle handle, long timeoutMs) throws Exception {
        return Serialization.deserialize(await(handle, timeoutMs));
    }

    /** 
     * Uploads an artifact to AegisFS and registers its metadata. 
     * Handles deduplication automatically based on SHA-256. 
     */
    public String uploadArtifact(byte[] data) throws Exception {
        String sha256 = HexUtil.encode(Hashing.sha256(data));
        
        // 1. Deduplication check
        if (agent.artifactRegistry().exists(sha256)) {
            log.info("Artifact {} already exists, skipping upload.", sha256);
            return sha256;
        }
        
        // 2. Write to AegisFS (Source of Truth)
        String fsPath = "/artifacts/" + sha256;
        fileSystem.write(fsPath, data);
        
        // 3. Propose metadata via Raft
        ArtifactRecord record = ArtifactRecord.newBuilder()
                .setArtifactId(sha256)
                .setFileName("artifact-" + sha256)
                .setSize(data.length)
                .setCreatedAt(System.currentTimeMillis())
                .setFsPath(fsPath)
                .setOwnerId(ByteString.copyFrom(self.toBytes()))
                .setStatus(com.aegisos.proto.ArtifactStatus.ARTIFACT_ACTIVE)
                .build();
                
        com.aegisos.proto.RegisterArtifact regCmd = com.aegisos.proto.RegisterArtifact.newBuilder()
                .setArtifact(record)
                .build();
                
        agent.consensus().propose(com.aegisos.proto.StateCommand.newBuilder()
                .setType(com.aegisos.proto.CommandType.REGISTER_ARTIFACT)
                .setPayload(regCmd.toByteString())
                .build()).get(10, java.util.concurrent.TimeUnit.SECONDS);
                
        log.info("Uploaded artifact {} ({} bytes)", sha256, data.length);
        return sha256;
    }

    /** Retrieves artifact metadata from the registry index. */
    public ArtifactRecord getArtifact(String sha256) {
        return agent.artifactRegistry().bySha256(sha256).orElse(null);
    }

    /** Downloads the raw artifact bytes from AegisFS. */
    public byte[] downloadArtifact(String sha256) throws Exception {
        String fsPath = "/artifacts/" + sha256;
        com.aegisos.proto.FileMetadata meta = fileSystem.fileIndex().byName(fsPath)
            .orElseThrow(() -> new IllegalArgumentException("Artifact not found in AegisFS: " + sha256));
        return fileSystem.read(fsPath);
    }
}

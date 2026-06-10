package com.aegisos.runtime;

import com.aegisos.core.identity.NodeId;
import com.aegisos.fs.AegisFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.net.URLClassLoader;

/**
 * Runs a single {@link AegisJob} on a virtual thread, applying any restore state first.
 * Lifecycle bookkeeping and Raft updates are handled by {@link ProcessRuntimeAgent}.
 */
public final class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final NodeId self;
    private final AegisFS fileSystem;
    private final ArtifactClassLoader artifactClassLoader;
    private final java.nio.file.Path workspaceRoot;
    private final java.util.Map<String, ProcessSupervisor> activeSupervisors = new java.util.concurrent.ConcurrentHashMap<>();

    public JobExecutor(NodeId self, AegisFS fileSystem, ArtifactClassLoader artifactClassLoader, java.nio.file.Path workspaceRoot) {
        this.self = self;
        this.fileSystem = fileSystem;
        this.artifactClassLoader = artifactClassLoader;
        this.workspaceRoot = workspaceRoot;
    }

    /** Deserializes, optionally restores, and runs the job, returning the serialized result. */
    public byte[] run(String jobId, long executionId, byte[] jobBytes, byte[] restoreState, int memoryMb, 
                      java.util.Map<String, String> mountPaths, java.util.function.Consumer<byte[]> checkpointListener) throws Exception {
        Object[] args = new Object[] {
            jobId, self.toBytes(), null, null, jobBytes, restoreState, null, null, executionId
        };
        java.nio.file.Path execRoot = workspaceRoot.resolve(jobId).resolve("exec-" + executionId);
        WorkspaceInfo workspace = new WorkspaceInfo(execRoot);
        ProcessSupervisor supervisor = new ProcessSupervisor(jobId, memoryMb, workspace);
        supervisor.setCheckpointListener(checkpointListener);
        activeSupervisors.put(jobId, supervisor);
        try {
            supervisor.mountArtifacts(mountPaths);
            return supervisor.runWorker(args);
        } finally {
            activeSupervisors.remove(jobId);
        }
    }

    /** Artifact-based execution with isolated classloader. */
    public byte[] runFromArtifact(String jobId, long executionId, String artifactId, String fsPath,
                                  String className, String[] artifactArgs,
                                  byte[] restoreState, int memoryMb, 
                                  java.util.Map<String, String> mountPaths, java.util.function.Consumer<byte[]> checkpointListener) throws Exception {
        byte[] artifactArgsBytes = Serialization.serialize(artifactArgs);
        String localJarPath = artifactClassLoader.getCache().resolve(artifactId, fsPath).toAbsolutePath().toString();
        Object[] args = new Object[] {
            jobId, self.toBytes(), artifactId, className, null, restoreState, artifactArgsBytes, localJarPath, executionId
        };
        java.nio.file.Path execRoot = workspaceRoot.resolve(jobId).resolve("exec-" + executionId);
        WorkspaceInfo workspace = new WorkspaceInfo(execRoot);
        ProcessSupervisor supervisor = new ProcessSupervisor(jobId, memoryMb, workspace);
        supervisor.setCheckpointListener(checkpointListener);
        activeSupervisors.put(jobId, supervisor);
        try {
            supervisor.mountArtifacts(mountPaths);
            return supervisor.runWorker(args);
        } finally {
            activeSupervisors.remove(jobId);
        }
    }
    
    public void cancelJob(String jobId) {
        log.info("JobExecutor asked to cancel job: {}. Active supervisors: {}", jobId, activeSupervisors.keySet());
        ProcessSupervisor supervisor = activeSupervisors.get(jobId);
        if (supervisor != null) {
            log.info("Supervisor found for job {}, calling kill()", jobId);
            supervisor.kill();
        } else {
            log.warn("No supervisor found for job {}", jobId);
        }
    }

    /** Captures the current checkpoint state of a (already-deserialized) job, or null. */
    public byte[] captureState(AegisJob<?> job) {
        Serializable state = job.captureState();
        return state == null ? null : Serialization.serialize(state);
    }

    public void close() {
        int count = activeSupervisors.size();
        for (ProcessSupervisor supervisor : activeSupervisors.values()) {
            supervisor.kill();
        }
    }
}

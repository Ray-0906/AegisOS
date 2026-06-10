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
    private final java.util.Map<String, ProcessSupervisor> activeSupervisors = new java.util.concurrent.ConcurrentHashMap<>();

    public JobExecutor(NodeId self, AegisFS fileSystem, ArtifactClassLoader artifactClassLoader) {
        this.self = self;
        this.fileSystem = fileSystem;
        this.artifactClassLoader = artifactClassLoader;
    }

    /** Deserializes, optionally restores, and runs the job, returning the serialized result. */
    public byte[] run(String jobId, byte[] jobBytes, byte[] restoreState, int memoryMb) throws Exception {
        Object[] args = new Object[] {
            jobId, self.toBytes(), null, null, jobBytes, restoreState, null, null
        };
        ProcessSupervisor supervisor = new ProcessSupervisor(self, jobId, memoryMb);
        activeSupervisors.put(jobId, supervisor);
        try {
            return supervisor.runWorker(args);
        } finally {
            activeSupervisors.remove(jobId);
        }
    }

    /** Artifact-based execution with isolated classloader. */
    public byte[] runFromArtifact(String jobId, String artifactId, String fsPath,
                                  String className, String[] artifactArgs,
                                  byte[] restoreState, int memoryMb) throws Exception {
        byte[] artifactArgsBytes = Serialization.serialize(artifactArgs);
        String localJarPath = artifactClassLoader.getCache().resolve(artifactId, fsPath).toAbsolutePath().toString();
        Object[] args = new Object[] {
            jobId, self.toBytes(), artifactId, className, null, restoreState, artifactArgsBytes, localJarPath
        };
        ProcessSupervisor supervisor = new ProcessSupervisor(self, jobId, memoryMb);
        activeSupervisors.put(jobId, supervisor);
        try {
            return supervisor.runWorker(args);
        } finally {
            activeSupervisors.remove(jobId);
        }
    }
    
    public void cancelJob(String jobId) {
        log.info("JobExecutor asked to cancel job: {}. Active supervisors: {}", jobId, activeSupervisors.keySet());
        // #region agent log
        com.aegisos.core.util.DebugLogger.log("JobExecutor.java:62", "cancelJob invoked",
            java.util.Map.of("jobId", jobId, "supervisorCount", activeSupervisors.size(), "supervisorFound", activeSupervisors.containsKey(jobId)), "A", "pre-fix");
        // #endregion
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
        // #region agent log
        com.aegisos.core.util.DebugLogger.log("JobExecutor.java:80", "JobExecutor.close() start",
            java.util.Map.of("activeSupervisorCount", count, "supervisorJobIds", activeSupervisors.keySet().toString()), "A", "pre-fix");
        // #endregion
        for (ProcessSupervisor supervisor : activeSupervisors.values()) {
            supervisor.kill();
        }
        // #region agent log
        com.aegisos.core.util.DebugLogger.log("JobExecutor.java:86", "JobExecutor.close() end",
            java.util.Map.of("remainingSupervisorCount", activeSupervisors.size()), "A", "pre-fix");
        // #endregion
    }
}

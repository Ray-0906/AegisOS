package com.aegisos.runtime;

import com.aegisos.core.identity.NodeId;
import com.aegisos.fs.AegisFS;

/** Execution context handed to a running {@link AegisJob}. */
public final class JobContext {

    private final String jobId;
    private final NodeId executingNode;
    private final AegisFS fileSystem;
    private final CheckpointEmitter checkpointEmitter;

    public JobContext(String jobId, NodeId executingNode, AegisFS fileSystem) {
        this(jobId, executingNode, fileSystem, null);
    }

    public JobContext(String jobId, NodeId executingNode, AegisFS fileSystem, CheckpointEmitter checkpointEmitter) {
        this.jobId = jobId;
        this.executingNode = executingNode;
        this.fileSystem = fileSystem;
        this.checkpointEmitter = checkpointEmitter;
    }

    public String jobId() {
        return jobId;
    }

    public NodeId executingNode() {
        return executingNode;
    }

    /** Cluster file system, for jobs that read or write distributed files. */
    public AegisFS fileSystem() {
        return fileSystem;
    }

    /** 
     * Initiates a stateful checkpoint. Internally delegates to the job's captureState()
     * and persists the binary payload to the cluster.
     */
    public void checkpoint() {
        if (checkpointEmitter != null) {
            checkpointEmitter.emitCheckpoint();
        }
    }

    public interface CheckpointEmitter {
        void emitCheckpoint();
    }
}

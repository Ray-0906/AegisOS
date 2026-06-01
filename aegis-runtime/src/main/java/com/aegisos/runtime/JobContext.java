package com.aegisos.runtime;

import com.aegisos.core.identity.NodeId;
import com.aegisos.fs.AegisFS;

/** Execution context handed to a running {@link AegisJob}. */
public final class JobContext {

    private final String jobId;
    private final NodeId executingNode;
    private final AegisFS fileSystem;

    public JobContext(String jobId, NodeId executingNode, AegisFS fileSystem) {
        this.jobId = jobId;
        this.executingNode = executingNode;
        this.fileSystem = fileSystem;
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
}

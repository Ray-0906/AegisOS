package com.aegisos.api;

import com.aegisos.fs.AegisFS;

/**
 * The public, OS-like entry point exposed to user programs and CLI tools
 * (design section 3.8). Obtained from a running node.
 */
public final class AegisOS {

    private final AegisFS fileSystem;
    private final ProcessManager processManager;
    private final ClusterInfo clusterInfo;

    public AegisOS(AegisFS fileSystem, ProcessManager processManager, ClusterInfo clusterInfo) {
        this.fileSystem = fileSystem;
        this.processManager = processManager;
        this.clusterInfo = clusterInfo;
    }

    public AegisFS getFileSystem() {
        return fileSystem;
    }

    public ProcessManager getProcessManager() {
        return processManager;
    }

    public ClusterInfo getClusterInfo() {
        return clusterInfo;
    }
}

package com.aegisos.runtime;

import java.nio.file.Path;

/**
 * Encapsulates the directory structure for a single job execution workspace.
 * Paths are structured as: /var/aegisos/workspaces/<job-id>/exec-<execution-id>/
 */
public final class WorkspaceInfo {
    
    private final Path root;

    public WorkspaceInfo(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path artifactsDir() {
        return root.resolve("artifacts");
    }

    public Path scratchDir() {
        return root.resolve("scratch");
    }

    public Path checkpointsDir() {
        return root.resolve("checkpoints");
    }

    public Path stdoutLog() {
        return root.resolve("stdout.log");
    }

    public Path stderrLog() {
        return root.resolve("stderr.log");
    }

    /** 
     * Creates all necessary directories for the workspace.
     */
    public void provision() throws java.io.IOException {
        java.nio.file.Files.createDirectories(artifactsDir());
        java.nio.file.Files.createDirectories(scratchDir());
        java.nio.file.Files.createDirectories(checkpointsDir());
    }
}

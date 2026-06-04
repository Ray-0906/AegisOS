package com.aegisos.runtime;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Factory for creating fresh, isolated ClassLoaders for artifact execution.
 */
public final class ArtifactClassLoader {

    private final ArtifactCache cache;

    public ArtifactClassLoader(ArtifactCache cache) {
        this.cache = cache;
    }

    /**
     * Creates a NEW isolated URLClassLoader for the given artifact.
     * The caller MUST close the returned loader in a try-with-resources block.
     *
     * The parent classloader is set to AegisJob's classloader, ensuring the
     * dynamically loaded class can resolve AegisOS API classes (AegisJob, JobContext)
     * but remains isolated from other artifacts.
     */
    public URLClassLoader createLoader(String artifactId, String fsPath) throws IOException {
        Path jar = cache.resolve(artifactId, fsPath);
        return new URLClassLoader(
                new URL[]{ jar.toUri().toURL() },
                AegisJob.class.getClassLoader()
        );
    }
}

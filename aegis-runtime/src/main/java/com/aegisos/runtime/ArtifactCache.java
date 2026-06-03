package com.aegisos.runtime;

import com.aegisos.core.crypto.Hashing;
import com.aegisos.core.util.HexUtil;
import com.aegisos.fs.AegisFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local disk cache for downloaded artifacts.
 * Verifies SHA-256 on first download. Subsequent loads from cache
 * use a quick size/mtime check to trust the local copy.
 */
public final class ArtifactCache {
    private static final Logger log = LoggerFactory.getLogger(ArtifactCache.class);

    private final Path cacheDir;
    private final AegisFS fileSystem;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public ArtifactCache(Path cacheDir, AegisFS fileSystem) {
        this.cacheDir = cacheDir;
        this.fileSystem = fileSystem;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create artifact cache dir", e);
        }
    }

    /**
     * Resolves an artifact locally, downloading it from AegisFS if necessary.
     */
    public Path resolve(String artifactId, String fsPath) throws IOException {
        Object lock = locks.computeIfAbsent(artifactId, k -> new Object());
        synchronized (lock) {
            Path local = cacheDir.resolve(artifactId + ".jar");
            Path meta = cacheDir.resolve(artifactId + ".meta");

        if (Files.exists(local) && Files.exists(meta)) {
            CachedMeta cached = readMeta(meta);
            long currentSize = Files.size(local);
            long currentMtime = Files.getLastModifiedTime(local).toMillis();
            if (cached != null && currentSize == cached.size && currentMtime == cached.mtime) {
                log.info("CACHE HIT: {}", artifactId);
                return local; // trust cache
            }
            log.warn("Cache integrity mismatch for {}, re-downloading", artifactId);
            Files.deleteIfExists(local);
        }

        log.info("CACHE MISS: {}", artifactId);
        log.info("Artifact {} not cached locally, fetching from {}", artifactId, fsPath);
        byte[] data = fileSystem.read(fsPath);
        if (data == null) {
            throw new IOException("Failed to read artifact from AegisFS: " + fsPath);
        }

        // Verify SHA-256
        String actualSha = HexUtil.encode(Hashing.sha256(data));
        if (!actualSha.equals(artifactId)) {
            throw new SecurityException(
                    "Artifact integrity check failed: expected " + artifactId + " but got " + actualSha);
        }

        // Atomic write
        Path tmp = local.resolveSibling(artifactId + "_" + System.nanoTime() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, local, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            writeMeta(meta, Files.size(local), Files.getLastModifiedTime(local).toMillis());

            return local;
        }
    }

    public boolean isCached(String artifactId) {
        return Files.exists(cacheDir.resolve(artifactId + ".jar"));
    }

    private CachedMeta readMeta(Path metaFile) {
        try {
            List<String> lines = Files.readAllLines(metaFile);
            if (lines.size() >= 2) {
                return new CachedMeta(Long.parseLong(lines.get(0)), Long.parseLong(lines.get(1)));
            }
        } catch (Exception e) {
            // ignore, return null to force re-download
        }
        return null;
    }

    private void writeMeta(Path metaFile, long size, long mtime) {
        try {
            Files.writeString(metaFile, size + "\n" + mtime + "\n");
        } catch (IOException e) {
            log.warn("Failed to write cache meta for {}", metaFile);
        }
    }

    private record CachedMeta(long size, long mtime) {}
}

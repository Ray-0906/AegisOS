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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Local disk cache for downloaded artifacts.
 * Verifies SHA-256 on first download. Subsequent loads from cache
 * use a quick size/mtime check to trust the local copy.
 * Supports LRU eviction, pinning, and configurable max size.
 */
public final class ArtifactCache {
    private static final Logger log = LoggerFactory.getLogger(ArtifactCache.class);

    private final Path cacheDir;
    private final AegisFS fileSystem;
    private final long maxSizeBytes;

    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    private final Object lruLock = new Object();
    private final Map<String, CacheEntry> index = new LinkedHashMap<>(16, 0.75f, true);
    private long currentSizeBytes = 0;

    public ArtifactCache(Path cacheDir, AegisFS fileSystem, long maxSizeBytes) {
        this.cacheDir = cacheDir;
        this.fileSystem = fileSystem;
        this.maxSizeBytes = maxSizeBytes;
        try {
            Files.createDirectories(cacheDir);
            recover();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create/recover artifact cache dir", e);
        }
    }

    private void recover() throws IOException {
        synchronized (lruLock) {
            try (Stream<Path> stream = Files.list(cacheDir)) {
                stream.filter(p -> p.toString().endsWith(".meta")).forEach(meta -> {
                    String name = meta.getFileName().toString();
                    String artifactId = name.substring(0, name.length() - 5);
                    Path local = cacheDir.resolve(artifactId + ".jar");
                    if (Files.exists(local)) {
                        CachedMeta cached = readMeta(meta);
                        if (cached != null) {
                            try {
                                long size = Files.size(local);
                                if (size == cached.size) {
                                    index.put(artifactId, new CacheEntry(size, cached.mtime));
                                    currentSizeBytes += size;
                                    return;
                                }
                            } catch (IOException e) {
                                // ignore
                            }
                        }
                        try {
                            Files.deleteIfExists(local);
                            Files.deleteIfExists(meta);
                        } catch (IOException e) {}
                    } else {
                        try { Files.deleteIfExists(meta); } catch (IOException e) {}
                    }
                });
            }
        }
        log.info("Recovered ArtifactCache: {} items, {} bytes", index.size(), currentSizeBytes);
    }

    public void pin(String artifactId) {
        synchronized (lruLock) {
            CacheEntry entry = index.get(artifactId);
            if (entry != null) {
                entry.pinCount++;
            }
        }
    }

    public void unpin(String artifactId) {
        synchronized (lruLock) {
            CacheEntry entry = index.get(artifactId);
            if (entry != null && entry.pinCount > 0) {
                entry.pinCount--;
            }
        }
    }

    private void reserveSpace(long sizeBytes) throws IOException {
        synchronized (lruLock) {
            if (sizeBytes > maxSizeBytes) {
                throw new IOException("Artifact too large for cache: " + sizeBytes + " > " + maxSizeBytes);
            }
            java.util.Iterator<Map.Entry<String, CacheEntry>> it = index.entrySet().iterator();
            while (currentSizeBytes + sizeBytes > maxSizeBytes && it.hasNext()) {
                Map.Entry<String, CacheEntry> entry = it.next();
                if (entry.getValue().pinCount == 0) {
                    currentSizeBytes -= entry.getValue().size;
                    it.remove();
                    try {
                        Files.deleteIfExists(cacheDir.resolve(entry.getKey() + ".jar"));
                        Files.deleteIfExists(cacheDir.resolve(entry.getKey() + ".meta"));
                        log.info("Evicted artifact {} from cache", entry.getKey());
                    } catch (IOException e) {
                        log.warn("Failed to delete evicted artifact files for {}", entry.getKey(), e);
                    }
                }
            }
            if (currentSizeBytes + sizeBytes > maxSizeBytes) {
                throw new IOException("Cache full, cannot reserve " + sizeBytes + " bytes (all remaining artifacts pinned?)");
            }
        }
    }

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
                    synchronized (lruLock) {
                        index.get(artifactId); // touch for LRU
                    }
                    return local;
                }
                log.warn("Cache integrity mismatch for {}, re-downloading", artifactId);
                Files.deleteIfExists(local);
                synchronized (lruLock) {
                    CacheEntry e = index.remove(artifactId);
                    if (e != null) currentSizeBytes -= e.size;
                }
            }

            log.info("CACHE MISS: {}", artifactId);
            byte[] data = fileSystem.read(fsPath);
            if (data == null) {
                throw new IOException("Failed to read artifact from AegisFS: " + fsPath);
            }

            String actualSha = HexUtil.encode(Hashing.sha256(data));
            if (!actualSha.equals(artifactId)) {
                throw new SecurityException("Artifact integrity check failed: expected " + artifactId + " but got " + actualSha);
            }

            reserveSpace(data.length);

            Path tmp = local.resolveSibling(artifactId + "_" + System.nanoTime() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, local, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            long mtime = Files.getLastModifiedTime(local).toMillis();
            writeMeta(meta, data.length, mtime);

            synchronized (lruLock) {
                index.put(artifactId, new CacheEntry(data.length, mtime));
                currentSizeBytes += data.length;
            }

            return local;
        }
    }

    public boolean isCached(String artifactId) {
        synchronized (lruLock) {
            return index.containsKey(artifactId);
        }
    }

    private CachedMeta readMeta(Path metaFile) {
        try {
            List<String> lines = Files.readAllLines(metaFile);
            if (lines.size() >= 2) {
                return new CachedMeta(Long.parseLong(lines.get(0)), Long.parseLong(lines.get(1)));
            }
        } catch (Exception e) {}
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

    private static class CacheEntry {
        final long size;
        final long mtime;
        int pinCount = 0;

        CacheEntry(long size, long mtime) {
            this.size = size;
            this.mtime = mtime;
        }
    }
}

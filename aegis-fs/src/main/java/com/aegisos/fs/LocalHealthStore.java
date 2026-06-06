package com.aegisos.fs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class LocalHealthStore {

    private static final Logger log = LoggerFactory.getLogger(LocalHealthStore.class);

    private final Path healthFile;
    private final ConcurrentHashMap<String, ChunkHealthRecord> store = new ConcurrentHashMap<>();

    public static final class ChunkHealthRecord {
        public volatile ReplicaState state;
        public volatile int failCount;

        public ChunkHealthRecord(ReplicaState state, int failCount) {
            this.state = state;
            this.failCount = failCount;
        }
    }

    public LocalHealthStore(Path dataDir) {
        this.healthFile = dataDir.resolveSibling("health.db");
        load();
    }

    private void load() {
        if (!Files.exists(healthFile)) {
            return;
        }
        try (Stream<String> lines = Files.lines(healthFile)) {
            lines.forEach(line -> {
                String[] parts = line.split(",");
                if (parts.length >= 3) {
                    try {
                        String chunkId = parts[0];
                        ReplicaState state = ReplicaState.valueOf(parts[1]);
                        int failCount = Integer.parseInt(parts[2]);
                        store.put(chunkId, new ChunkHealthRecord(state, failCount));
                    } catch (Exception e) {
                        log.warn("Failed to parse health record line: {}", line);
                    }
                }
            });
        } catch (IOException e) {
            log.warn("Failed to load local health store: {}", e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Path tmpFile = healthFile.resolveSibling("health.db.tmp");
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, ChunkHealthRecord> entry : store.entrySet()) {
                sb.append(entry.getKey()).append(",")
                  .append(entry.getValue().state.name()).append(",")
                  .append(entry.getValue().failCount).append("\n");
            }
            Files.writeString(tmpFile, sb.toString());
            Files.move(tmpFile, healthFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to save local health store: {}", e.getMessage());
        }
    }

    public ReplicaState getState(String chunkId) {
        ChunkHealthRecord rec = store.get(chunkId);
        return rec != null ? rec.state : ReplicaState.UNKNOWN;
    }

    public void reportHealthy(String chunkId) {
        ChunkHealthRecord rec = store.computeIfAbsent(chunkId, k -> new ChunkHealthRecord(ReplicaState.HEALTHY, 0));
        boolean changed = false;
        if (rec.state != ReplicaState.HEALTHY || rec.failCount > 0) {
            rec.state = ReplicaState.HEALTHY;
            rec.failCount = 0;
            changed = true;
        }
        if (changed) save();
    }

    public void reportFailure(String chunkId, boolean isCorrupt) {
        ChunkHealthRecord rec = store.computeIfAbsent(chunkId, k -> new ChunkHealthRecord(ReplicaState.HEALTHY, 0));
        boolean changed = false;
        
        if (rec.state == ReplicaState.HEALTHY) {
            rec.state = ReplicaState.SUSPECT;
            rec.failCount = 1;
            changed = true;
        } else if (rec.state == ReplicaState.SUSPECT) {
            rec.failCount++;
            if (rec.failCount >= 3) {
                rec.state = isCorrupt ? ReplicaState.CORRUPT : ReplicaState.MISSING;
            }
            changed = true;
        }
        
        if (changed) save();
    }

    public void reportQuarantined(String chunkId) {
        ChunkHealthRecord rec = store.computeIfAbsent(chunkId, k -> new ChunkHealthRecord(ReplicaState.QUARANTINED, 0));
        if (rec.state != ReplicaState.QUARANTINED) {
            rec.state = ReplicaState.QUARANTINED;
            save();
        }
    }

    public void remove(String chunkId) {
        if (store.remove(chunkId) != null) {
            save();
        }
    }
    
    public Map<String, ChunkHealthRecord> all() {
        return Map.copyOf(store);
    }
}

package com.aegisos.fs;

import com.aegisos.core.util.HexUtil;
import com.aegisos.proto.FileMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The replicated file-metadata view, populated by applying committed REGISTER_FILE
 * commands from the Raft log. A metadata entry with negative size acts as a tombstone
 * (delete).
 */
public final class FileIndex {

    private static final Logger log = LoggerFactory.getLogger(FileIndex.class);

    private final ConcurrentHashMap<String, FileMetadata> byFileId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> nameToFileId = new ConcurrentHashMap<>();

    public void applyRegisterFile(FileMetadata metadata) {
        String fileId = HexUtil.encode(metadata.getFileId().toByteArray());
        if (metadata.getSize() < 0) {
            byFileId.remove(fileId);
            nameToFileId.remove(metadata.getName());
            log.debug("Tombstoned file {}", metadata.getName());
            return;
        }
        byFileId.put(fileId, metadata);
        nameToFileId.put(metadata.getName(), fileId);
        log.debug("Registered file {} ({} chunks)", metadata.getName(), metadata.getChunksCount());
    }

    public Optional<FileMetadata> byName(String name) {
        String fileId = nameToFileId.get(name);
        return fileId == null ? Optional.empty() : Optional.ofNullable(byFileId.get(fileId));
    }

    public Optional<FileMetadata> byFileId(String fileIdHex) {
        return Optional.ofNullable(byFileId.get(fileIdHex));
    }

    public List<FileMetadata> list(String prefix) {
        List<FileMetadata> out = new ArrayList<>();
        for (var e : nameToFileId.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                FileMetadata m = byFileId.get(e.getValue());
                if (m != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    public List<FileMetadata> all() {
        return new ArrayList<>(byFileId.values());
    }
}

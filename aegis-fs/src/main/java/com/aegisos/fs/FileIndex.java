package com.aegisos.fs;

import com.aegisos.consensus.SnapshotException;
import com.aegisos.consensus.SnapshotParticipant;
import com.aegisos.core.util.HexUtil;
import com.aegisos.proto.FileMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The replicated file-metadata view, populated by applying committed REGISTER_FILE
 * commands from the Raft log. A metadata entry with negative size acts as a tombstone
 * (delete).
 */
public final class FileIndex implements SnapshotParticipant {

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
        
        String existingFileId = nameToFileId.get(metadata.getName());
        if (existingFileId != null && !existingFileId.equals(fileId)) {
            byFileId.remove(existingFileId);
            log.debug("Overwriting file {}, removing old metadata {}", metadata.getName(), existingFileId);
        }
        
        byFileId.put(fileId, metadata);
        nameToFileId.put(metadata.getName(), fileId);
        log.debug("Registered file {} ({} chunks)", metadata.getName(), metadata.getChunksCount());
    }

    public void applyAddReplica(com.aegisos.proto.AddReplica cmd) {
        String fileId = HexUtil.encode(cmd.getFileId().toByteArray());
        FileMetadata meta = byFileId.get(fileId);
        if (meta == null) return;
        
        FileMetadata.Builder builder = meta.toBuilder();
        for (int i = 0; i < builder.getChunksCount(); i++) {
            com.aegisos.proto.ChunkRef ref = builder.getChunks(i);
            if (java.util.Arrays.equals(ref.getChunkId().toByteArray(), cmd.getChunkId().toByteArray())) {
                boolean alreadyHas = false;
                for (com.google.protobuf.ByteString existing : ref.getNodeIdsList()) {
                    if (java.util.Arrays.equals(existing.toByteArray(), cmd.getNodeId().toByteArray())) {
                        alreadyHas = true;
                        break;
                    }
                }
                if (!alreadyHas) {
                    com.aegisos.proto.ChunkRef newRef = ref.toBuilder().addNodeIds(cmd.getNodeId()).build();
                    builder.setChunks(i, newRef);
                    byFileId.put(fileId, builder.build());
                    log.info("Added replica {} to chunk {}", HexUtil.encode(cmd.getNodeId().toByteArray()), HexUtil.encode(cmd.getChunkId().toByteArray()));
                }
                break;
            }
        }
    }

    public void applyRemoveReplica(com.aegisos.proto.RemoveReplica cmd) {
        String fileId = HexUtil.encode(cmd.getFileId().toByteArray());
        FileMetadata meta = byFileId.get(fileId);
        if (meta == null) return;
        
        FileMetadata.Builder builder = meta.toBuilder();
        for (int i = 0; i < builder.getChunksCount(); i++) {
            com.aegisos.proto.ChunkRef ref = builder.getChunks(i);
            if (java.util.Arrays.equals(ref.getChunkId().toByteArray(), cmd.getChunkId().toByteArray())) {
                com.aegisos.proto.ChunkRef.Builder refBuilder = ref.toBuilder().clearNodeIds();
                boolean removed = false;
                for (com.google.protobuf.ByteString existing : ref.getNodeIdsList()) {
                    if (java.util.Arrays.equals(existing.toByteArray(), cmd.getNodeId().toByteArray())) {
                        removed = true;
                    } else {
                        refBuilder.addNodeIds(existing);
                    }
                }
                if (removed) {
                    builder.setChunks(i, refBuilder.build());
                    byFileId.put(fileId, builder.build());
                    log.info("Removed replica {} from chunk {}", HexUtil.encode(cmd.getNodeId().toByteArray()), HexUtil.encode(cmd.getChunkId().toByteArray()));
                }
                break;
            }
        }
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

    // --- SnapshotParticipant ---

    @Override public String id() { return "file-index"; }

    @Override
    public synchronized byte[] snapshot() throws SnapshotException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            var values = new ArrayList<>(byFileId.values());
            out.writeInt(values.size());
            for (FileMetadata m : values) {
                byte[] bytes = m.toByteArray();
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new SnapshotException("Failed to snapshot FileIndex", e);
        }
    }

    @Override
    public synchronized void restore(byte[] data) throws SnapshotException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            byFileId.clear();
            nameToFileId.clear();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int len = in.readInt();
                byte[] buf = new byte[len];
                in.readFully(buf);
                FileMetadata m = FileMetadata.parseFrom(buf);
                String fileId = HexUtil.encode(m.getFileId().toByteArray());
                byFileId.put(fileId, m);
                nameToFileId.put(m.getName(), fileId);
            }
            log.info("Restored FileIndex: {} files", byFileId.size());
        } catch (IOException e) {
            throw new SnapshotException("Failed to restore FileIndex", e);
        }
    }
}

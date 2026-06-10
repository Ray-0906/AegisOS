package com.aegisos.fs.audit;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.util.HexUtil;
import com.aegisos.fs.FileIndex;
import com.aegisos.proto.ChunkRef;
import com.aegisos.proto.FileMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Extracts chunk-level authoritative metadata from the FileIndex.
 * 
 * Part of the Storage Audit Framework (Sprint 2 - Step 1).
 * Strictly read-only.
 */
public class ChunkMetadataInventory {

    private final FileIndex fileIndex;

    public ChunkMetadataInventory(FileIndex fileIndex) {
        this.fileIndex = fileIndex;
    }

    public List<ChunkInventoryRecord> build() {
        List<ChunkInventoryRecord> records = new ArrayList<>();
        for (FileMetadata file : fileIndex.all()) {
            if (file.getSize() < 0) continue; // Skip deleted files (tombstones)
            for (ChunkRef chunk : file.getChunksList()) {
                String chunkId = HexUtil.encode(chunk.getChunkId().toByteArray());
                List<NodeId> expectedReplicas = chunk.getNodeIdsList().stream()
                        .map(b -> NodeId.of(b.toByteArray()))
                        .collect(Collectors.toList());
                records.add(new ChunkInventoryRecord(chunkId, file.getReplication(), expectedReplicas));
            }
        }
        return records;
    }

    public static class ChunkInventoryRecord {
        private final String chunkIdHex;
        private final int requiredReplicationFactor;
        private final List<NodeId> expectedReplicaNodes;

        public ChunkInventoryRecord(String chunkIdHex, int requiredReplicationFactor, List<NodeId> expectedReplicaNodes) {
            this.chunkIdHex = chunkIdHex;
            this.requiredReplicationFactor = requiredReplicationFactor;
            this.expectedReplicaNodes = expectedReplicaNodes;
        }

        public String chunkIdHex() { return chunkIdHex; }
        public int requiredReplicationFactor() { return requiredReplicationFactor; }
        public List<NodeId> expectedReplicaNodes() { return expectedReplicaNodes; }
    }
}

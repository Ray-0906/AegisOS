package com.aegisos.fs.audit;

import com.aegisos.core.identity.NodeId;
import com.aegisos.fs.FileIndex;
import com.aegisos.proto.ChunkRef;
import com.aegisos.proto.FileMetadata;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ChunkMetadataInventoryTest {

    @Test
    public void testExtractsChunksCorrectly() {
        FileIndex index = new FileIndex();

        NodeId n1 = NodeId.of(new byte[32]);
        byte[] n2Bytes = new byte[32];
        n2Bytes[0] = 1;
        NodeId n2 = NodeId.of(n2Bytes);

        ChunkRef ref1 = ChunkRef.newBuilder()
                .setChunkId(ByteString.copyFrom(new byte[]{1, 2, 3}))
                .addNodeIds(ByteString.copyFrom(n1.toBytes()))
                .addNodeIds(ByteString.copyFrom(n2.toBytes()))
                .build();

        FileMetadata metadata = FileMetadata.newBuilder()
                .setName("/test.txt")
                .setFileId(ByteString.copyFrom(new byte[]{9, 9}))
                .setSize(100)
                .setReplication(3)
                .addChunks(ref1)
                .build();

        index.applyRegisterFile(metadata);

        ChunkMetadataInventory inventory = new ChunkMetadataInventory(index);
        List<ChunkMetadataInventory.ChunkInventoryRecord> records = inventory.build();

        assertEquals(1, records.size());
        ChunkMetadataInventory.ChunkInventoryRecord rec = records.get(0);
        assertEquals(com.aegisos.core.util.HexUtil.encode(new byte[]{1, 2, 3}), rec.chunkIdHex());
        assertEquals(3, rec.requiredReplicationFactor());
        assertEquals(2, rec.expectedReplicaNodes().size());
        assertTrue(rec.expectedReplicaNodes().contains(n1));
        assertTrue(rec.expectedReplicaNodes().contains(n2));
    }

    @Test
    public void testIgnoresTombstones() {
        FileIndex index = new FileIndex();

        ChunkRef ref1 = ChunkRef.newBuilder()
                .setChunkId(ByteString.copyFrom(new byte[]{1, 2, 3}))
                .build();

        FileMetadata metadata = FileMetadata.newBuilder()
                .setName("/test.txt")
                .setFileId(ByteString.copyFrom(new byte[]{9, 9}))
                .setSize(-1) // Tombstone
                .setReplication(3)
                .addChunks(ref1)
                .build();

        index.applyRegisterFile(metadata);

        ChunkMetadataInventory inventory = new ChunkMetadataInventory(index);
        List<ChunkMetadataInventory.ChunkInventoryRecord> records = inventory.build();

        assertEquals(0, records.size(), "Deleted files should be ignored");
    }
}

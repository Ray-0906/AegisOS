package com.aegisos.fs;

import com.aegisos.consensus.ConsensusModule;
import com.aegisos.core.crypto.Hashing;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.util.HexUtil;
import com.aegisos.discovery.DiscoveryService;
import com.aegisos.network.NetworkLayer;
import com.aegisos.proto.ChunkRef;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.FileMetadata;
import com.aegisos.proto.StateCommand;
import com.aegisos.proto.RepairChunk;
import com.aegisos.proto.RepairComplete;
import com.aegisos.fs.audit.RepairTaskStore;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Distributed, content-addressed, encrypted file system (design section 3.5).
 * Chunks are stored on N nodes; file metadata lives in the Raft log.
 */
public final class AegisFS implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AegisFS.class);
    private static final long COMMIT_TIMEOUT_MS = 10_000;

    private final ConsensusModule consensus;
    private final DiscoveryService discovery;
    private final NodeId self;
    private final int replicationFactor;

    private final ChunkSplitter splitter = new ChunkSplitter();
    private final ChunkCipher cipher;
    private final ChunkStore store;
    private final ChunkReplicator replicator;
    private final ChunkPlacement placement;
    private final FileIndex fileIndex = new FileIndex();
    private final RepairTaskStore repairTaskStore = new RepairTaskStore();

    private final LocalHealthStore localHealth;
    private final ChunkScrubber scrubber;
    private final AntiEntropyManager antiEntropy;

    public AegisFS(NetworkLayer network, ConsensusModule consensus, DiscoveryService discovery,
                   NodeId self, byte[] clusterKey, int replicationFactor, Path chunkDir) {
        this.consensus = consensus;
        this.discovery = discovery;
        this.self = self;
        this.replicationFactor = replicationFactor;
        this.cipher = new ChunkCipher(clusterKey);
        this.store = new ChunkStore(chunkDir);
        this.replicator = new ChunkReplicator(network, store, self, discovery);
        this.placement = new ChunkPlacement(discovery, self);
        
        this.localHealth = new LocalHealthStore(chunkDir);
        this.scrubber = new ChunkScrubber(this, this.localHealth, self);
        this.antiEntropy = new AntiEntropyManager(this, this.localHealth, self, consensus);
    }

    public void registerAppliers() {
        consensus.stateMachine().register(CommandType.REGISTER_FILE, (index, cmd) -> {
            try {
                fileIndex.applyRegisterFile(FileMetadata.parseFrom(cmd.getPayload()));
            } catch (Exception e) {
                log.warn("bad REGISTER_FILE at {}", index);
            }
        });
        consensus.stateMachine().register(CommandType.ADD_REPLICA, (index, cmd) -> {
            try {
                fileIndex.applyAddReplica(com.aegisos.proto.AddReplica.parseFrom(cmd.getPayload()));
            } catch (Exception e) {
                log.warn("bad ADD_REPLICA at {}", index);
            }
        });
        consensus.stateMachine().register(CommandType.REMOVE_REPLICA, (index, cmd) -> {
            try {
                fileIndex.applyRemoveReplica(com.aegisos.proto.RemoveReplica.parseFrom(cmd.getPayload()));
            } catch (Exception e) {
                log.warn("bad REMOVE_REPLICA at {}", index);
            }
        });
        consensus.stateMachine().register(CommandType.REPAIR_CHUNK, (index, cmd) -> {
            try {
                RepairChunk repair = RepairChunk.parseFrom(cmd.getPayload());
                repairTaskStore.applyRepairChunk(index, repair);
                log.info("REPAIR_CHUNK at {}: task {} created for chunk {}",
                    index, repair.getRepairId(), HexUtil.encode(repair.getChunkId().toByteArray()));
            } catch (Exception e) {
                log.warn("bad REPAIR_CHUNK at {}", index);
            }
        });
        consensus.stateMachine().register(CommandType.REPAIR_COMPLETE, (index, cmd) -> {
            try {
                RepairComplete complete = RepairComplete.parseFrom(cmd.getPayload());
                Optional<RepairTaskStore.RepairTask> task =
                    repairTaskStore.pendingByRepairId(complete.getRepairId());
                if (task.isEmpty()) {
                    log.info("REPAIR_COMPLETE at {} ignored: no PENDING task for {}",
                        index, complete.getRepairId());
                    return;
                }

                fileIndex.applyAddReplica(com.aegisos.proto.AddReplica.newBuilder()
                    .setFileId(complete.getFileId())
                    .setChunkId(complete.getChunkId())
                    .setNodeId(complete.getTargetNodeId())
                    .build());
                repairTaskStore.applyRepairComplete(index, complete);
            } catch (Exception e) {
                log.warn("bad REPAIR_COMPLETE at {}", index);
            }
        });
    }

    public void start() {
        replicator.start();
        scrubber.start();
        antiEntropy.start();
        log.info("AegisFS started (replication factor {})", replicationFactor);
    }

    public ChunkStore chunkStore() {
        return store;
    }

    public FileIndex fileIndex() {
        return fileIndex;
    }

    public RepairTaskStore repairTaskStore() {
        return repairTaskStore;
    }

    public LocalHealthStore localHealth() {
        return localHealth;
    }

    /** Stores an already-encrypted chunk on a target node (used by self-healing). */
    public boolean replicateChunk(NodeId target, byte[] chunkId, byte[] data) {
        return replicator.storeOn(target, chunkId, data);
    }

    /** Fetches an encrypted chunk from a source node. */
    public byte[] fetchChunk(NodeId source, byte[] chunkId) {
        return replicator.fetchFrom(source, chunkId);
    }



    /** Returns true if the chunk's replication factor in FileIndex is strictly less than required. */
    public boolean isStillUnderReplicated(byte[] chunkId, byte[] fileId) {
        String fileIdHex = HexUtil.encode(fileId);
        Optional<FileMetadata> metaOpt = fileIndex.byFileId(fileIdHex);
        if (metaOpt.isEmpty()) {
            return false;
        }
        FileMetadata meta = metaOpt.get();
        for (ChunkRef ref : meta.getChunksList()) {
            if (java.util.Arrays.equals(ref.getChunkId().toByteArray(), chunkId)) {
                return ref.getNodeIdsCount() < meta.getReplication();
            }
        }
        return false;
    }

    /** Writes a file into the cluster and returns its file id. */
    public byte[] write(String name, byte[] data) throws IOException {
        List<byte[]> chunks = splitter.split(data);
        List<ChunkRef> refs = new ArrayList<>(chunks.size());

        for (byte[] chunk : chunks) {
            ChunkCipher.EncryptedChunk enc = cipher.encrypt(chunk);
            byte[] chunkId = Hashing.sha256(enc.ciphertext());
            List<NodeId> targets = placement.selectTargets(chunkId, replicationFactor);

            List<ByteString> storedOn = new ArrayList<>();
            for (NodeId target : targets) {
                if (replicator.storeOn(target, chunkId, enc.ciphertext())) {
                    storedOn.add(ByteString.copyFrom(target.toBytes()));
                }
            }
            if (storedOn.size() < replicationFactor) {
                int available = discovery.membership().storageNodeCount();
                throw new IOException(String.format(
                        "Replication requirement not met.\nReplication factor = %d\nAvailable nodes = %d\nNeed at least %d alive nodes.",
                        replicationFactor, available, replicationFactor));
            }
            refs.add(ChunkRef.newBuilder()
                    .setChunkId(ByteString.copyFrom(chunkId))
                    .addAllNodeIds(storedOn)
                    .setEncryptedKey(ByteString.copyFrom(enc.wrappedKey()))
                    .setNonce(ByteString.copyFrom(enc.chunkNonce()))
                    .setPlainSize(chunk.length)
                    .build());
        }

        long createdAt = System.currentTimeMillis();
        byte[] fileId = Hashing.sha256(name.getBytes(StandardCharsets.UTF_8), self.toBytes(),
                ByteBuffer.allocate(Long.BYTES).putLong(createdAt).array());

        FileMetadata metadata = FileMetadata.newBuilder()
                .setFileId(ByteString.copyFrom(fileId))
                .setName(name)
                .addAllChunks(refs)
                .setSize(data.length)
                .setCreatedAt(createdAt)
                .setReplication(replicationFactor)
                .setOwnerId(ByteString.copyFrom(self.toBytes()))
                .build();

        commit(StateCommand.newBuilder()
                .setType(CommandType.REGISTER_FILE)
                .setPayload(metadata.toByteString())
                .build());
        log.info("Wrote {} ({} bytes, {} chunks)", name, data.length, refs.size());
        return fileId;
    }

    /** Reads a file back, verifying chunk integrity and decrypting each chunk. */
    public byte[] read(String name) throws IOException {
        FileMetadata metadata = fileIndex.byName(name)
                .orElseThrow(() -> new IOException("no such file: " + name));

        List<byte[]> plaintextChunks = new ArrayList<>(metadata.getChunksCount());
        for (ChunkRef ref : metadata.getChunksList()) {
            byte[] chunkId = ref.getChunkId().toByteArray();
            byte[] encrypted = null;
            for (ByteString nodeBytes : ref.getNodeIdsList()) {
                NodeId node = NodeId.of(nodeBytes.toByteArray());
                byte[] candidate = replicator.fetchFrom(node, chunkId);
                if (candidate != null && java.util.Arrays.equals(Hashing.sha256(candidate), chunkId)) {
                    encrypted = candidate;
                    break;
                }
            }
            if (encrypted == null) {
                log.error("Failed to read chunk {}. FileMetadata: {}", HexUtil.encode(chunkId), metadata);
                throw new IOException("no healthy replica for a chunk of " + name);
            }
            plaintextChunks.add(cipher.decrypt(encrypted, ref.getNonce().toByteArray(),
                    ref.getEncryptedKey().toByteArray()));
        }
        return splitter.reassemble(plaintextChunks);
    }

    public List<FileMetadata> list(String prefix) {
        return fileIndex.list(prefix);
    }

    public void delete(String name) throws IOException {
        Optional<FileMetadata> existing = fileIndex.byName(name);
        if (existing.isEmpty()) {
            return;
        }
        FileMetadata tombstone = existing.get().toBuilder().setSize(-1).clearChunks().build();
        commit(StateCommand.newBuilder()
                .setType(CommandType.REGISTER_FILE)
                .setPayload(tombstone.toByteString())
                .build());
    }

    private void commit(StateCommand command) throws IOException {
        try {
            long index = consensus.propose(command).get(COMMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            consensus.awaitApplied(index).get(COMMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IOException("failed to commit file metadata: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            antiEntropy.close();
        } catch (Exception e) {
            log.warn("Error closing antiEntropy: {}", e.toString());
        }
        try {
            scrubber.close();
        } catch (Exception e) {
            log.warn("Error closing scrubber: {}", e.toString());
        }
    }
}

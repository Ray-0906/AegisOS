package com.aegisos.fs;

import com.aegisos.consensus.ConsensusModule;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.util.HexUtil;
import com.aegisos.proto.ChunkRef;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.FileMetadata;
import com.aegisos.proto.RemoveReplica;
import com.aegisos.proto.StateCommand;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AntiEntropyManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AntiEntropyManager.class);

    private final AegisFS fs;
    private final LocalHealthStore localHealth;
    private final NodeId self;
    private final ConsensusModule consensus;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aegis-anti-entropy");
                t.setDaemon(true);
                return t;
            });

    public AntiEntropyManager(AegisFS fs, LocalHealthStore localHealth, NodeId self, ConsensusModule consensus) {
        this.fs = fs;
        this.localHealth = localHealth;
        this.self = self;
        this.consensus = consensus;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::reconcileSafe, 30, 30, TimeUnit.SECONDS);
        log.info("AntiEntropyManager started");
    }

    private void reconcileSafe() {
        try {
            reconcile();
        } catch (Exception e) {
            log.warn("AntiEntropyManager error: {}", e.toString());
        }
    }

    private void reconcile() {
        try {
            if (fs == null || fs.fileIndex() == null) return;

            List<FileMetadata> allFiles = fs.fileIndex().all();
            Set<String> expectedLocalChunks = new HashSet<>();
            Set<String> allGlobalChunks = new HashSet<>();

            // Build global and local expected state
            for (FileMetadata file : allFiles) {
                for (ChunkRef ref : file.getChunksList()) {
                    String hexId = HexUtil.encode(ref.getChunkId().toByteArray());
                    allGlobalChunks.add(hexId);
                    
                    boolean amReplica = false;
                    for (ByteString nodeByteString : ref.getNodeIdsList()) {
                        if (NodeId.of(nodeByteString.toByteArray()).equals(self)) {
                            amReplica = true;
                            break;
                        }
                    }
                    if (amReplica) {
                        expectedLocalChunks.add(hexId);
                    }
                }
            }

            // 1. Orphan Cleanup
            List<String> physicalChunks = fs.chunkStore().listChunkIds();
            log.debug("Node {} physical chunks: {}", self.toString(), physicalChunks);
            log.debug("Node {} expected chunks: {}", self.toString(), expectedLocalChunks);
            
            for (String physicalId : physicalChunks) {
                if (!expectedLocalChunks.contains(physicalId)) {
                    boolean older = fs.chunkStore().isOlderThan(HexUtil.decode(physicalId), 30_000);
                    log.debug("Node {} checking orphan {}. isOlderThan: {}", self.toString(), physicalId, older);
                    if (older) {
                        log.info("Orphan chunk detected locally: {}. Quarantining.", physicalId);
                        fs.chunkStore().quarantine(HexUtil.decode(physicalId));
                        localHealth.remove(physicalId);
                    }
                }
            }

            // 2. Report Missing/Corrupt to Leader
            Map<String, LocalHealthStore.ChunkHealthRecord> healthMap = localHealth.all();
            for (Map.Entry<String, LocalHealthStore.ChunkHealthRecord> entry : healthMap.entrySet()) {
                String chunkHexId = entry.getKey();
                ReplicaState state = entry.getValue().state;

                if ((state == ReplicaState.MISSING || state == ReplicaState.CORRUPT) && expectedLocalChunks.contains(chunkHexId)) {
                    log.warn("Chunk {} is conclusively {}. Reporting to leader.", chunkHexId, state);
                    
                    if (state == ReplicaState.CORRUPT) {
                        // Quarantine it locally so we don't accidentally serve it or read it
                        fs.chunkStore().quarantine(HexUtil.decode(chunkHexId));
                        localHealth.reportQuarantined(chunkHexId);
                    }

                    // Find the associated file metadata
                    FileMetadata targetFile = null;
                    for (FileMetadata file : allFiles) {
                        for (ChunkRef ref : file.getChunksList()) {
                            if (HexUtil.encode(ref.getChunkId().toByteArray()).equals(chunkHexId)) {
                                targetFile = file;
                                break;
                            }
                        }
                        if (targetFile != null) break;
                    }

                    if (targetFile != null) {
                        proposeRemoval(targetFile.getFileId().toByteArray(), HexUtil.decode(chunkHexId));
                    }
                }
            }
        } catch (Throwable t) {
            log.error("reconcile FAIL!", t);
        }
    }

    private void proposeRemoval(byte[] fileId, byte[] chunkId) {
        RemoveReplica cmd = RemoveReplica.newBuilder()
                .setFileId(ByteString.copyFrom(fileId))
                .setChunkId(ByteString.copyFrom(chunkId))
                .setNodeId(ByteString.copyFrom(self.toBytes()))
                .build();

        try {
            consensus.propose(StateCommand.newBuilder()
                    .setType(CommandType.REMOVE_REPLICA)
                    .setPayload(cmd.toByteString())
                    .build())
                .exceptionally(e -> {
                    log.error("Failed to propose REMOVE_REPLICA async: {}", e.getMessage(), e);
                    return -1L;
                });
            // No need to block on .get(), fire and forget. The state machine will process it.
        } catch (Exception e) {
            log.debug("Failed to propose REMOVE_REPLICA: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}

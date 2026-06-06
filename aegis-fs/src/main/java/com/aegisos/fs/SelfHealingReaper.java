package com.aegisos.fs;

import com.aegisos.consensus.ConsensusModule;
import com.aegisos.core.identity.NodeId;
import com.aegisos.discovery.DiscoveryService;
import com.aegisos.proto.ChunkRef;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.FileMetadata;
import com.aegisos.proto.PeerStatus;
import com.aegisos.proto.StateCommand;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background self-healing (design section 3.5). Periodically scans local chunks and, for
 * any under-replicated chunk this node still holds, transfers a fresh copy to another
 * node and records the updated placement in the Raft log.
 *
 * <p>To avoid every holder healing at once, only the alive holder with the smallest node
 * id performs the repair for a given chunk.
 */
public final class SelfHealingReaper implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingReaper.class);
    public static final long DEFAULT_INTERVAL_MS = 60_000;

    private final AegisFS fs;
    private final ConsensusModule consensus;
    private final DiscoveryService discovery;
    private final NodeId self;
    private final int replicationFactor;
    private final long intervalMs;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofVirtual().unstarted(r));

    public SelfHealingReaper(AegisFS fs, ConsensusModule consensus, DiscoveryService discovery,
                             NodeId self, int replicationFactor, long intervalMs) {
        this.fs = fs;
        this.consensus = consensus;
        this.discovery = discovery;
        this.self = self;
        this.replicationFactor = replicationFactor;
        this.intervalMs = intervalMs;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::scanSafe, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Self-healing reaper started (interval {}ms)", intervalMs);
    }

    private void scanSafe() {
        try {
            scan();
        } catch (Exception e) {
            log.warn("Reaper scan error: {}", e.toString());
        }
    }

    private void scan() {
        for (FileMetadata file : fs.fileIndex().all()) {
            for (int i = 0; i < file.getChunksCount(); i++) {
                ChunkRef ref = file.getChunks(i);
                byte[] chunkIdBytes = ref.getChunkId().toByteArray();
                String hexId = com.aegisos.core.util.HexUtil.encode(chunkIdBytes);
                
                List<NodeId> aliveHolders = aliveHolders(ref);
                if (aliveHolders.size() >= replicationFactor) {
                    continue;
                }
                
                if (aliveHolders.isEmpty()) {
                    // Everyone will log this if the chunk is totally lost. We just do it on the leader to avoid spam, or do it on self.
                    // The user wants "Repair refused"
                    log.warn("Repair refused for chunk {}: no healthy holders exist", hexId);
                    continue;
                } else {
                    log.info("Repair cannot be refused yet. aliveHolders size is {}: {}", aliveHolders.size(), aliveHolders);
                }
                
                // If we are corrupt or missing, we can't heal it.
                if (fs.localHealth().getState(hexId) != ReplicaState.HEALTHY) {
                    continue;
                }

                if (!isHealerFor(aliveHolders)) {
                    continue; // another holder is responsible
                }
                
                NodeId newTarget = healChunk(ref, aliveHolders);
                if (newTarget != null) {
                    proposeAddReplica(file.getFileId().toByteArray(), chunkIdBytes, newTarget.toBytes());
                }
            }
        }
    }

    private List<NodeId> aliveHolders(ChunkRef ref) {
        List<NodeId> alive = new ArrayList<>();
        for (ByteString nb : ref.getNodeIdsList()) {
            NodeId id = NodeId.of(nb.toByteArray());
            if (id.equals(self) || discovery.membership().statusOf(id) == PeerStatus.ALIVE) {
                alive.add(id);
            }
        }
        return alive;
    }

    private boolean isHealerFor(List<NodeId> aliveHolders) {
        NodeId min = self;
        for (NodeId holder : aliveHolders) {
            if (holder.toHex().compareTo(min.toHex()) < 0) {
                min = holder;
            }
        }
        return min.equals(self);
    }

    private NodeId healChunk(ChunkRef ref, List<NodeId> aliveHolders) {
        byte[] chunkId = ref.getChunkId().toByteArray();
        byte[] data = fs.chunkStore().get(chunkId);
        if (data == null) {
            return null;
        }
        for (NodeId target : discovery.membership().alivePeerIds()) {
            if (aliveHolders.contains(target)) {
                continue;
            }
            if (fs.replicateChunk(target, chunkId, data)) {
                log.info("Re-replicated chunk {} to {}", com.aegisos.core.util.HexUtil.shortId(chunkId), target.shortId());
                return target;
            }
        }
        return null; // nothing added
    }

    private void proposeAddReplica(byte[] fileId, byte[] chunkId, byte[] targetNodeId) {
        try {
            com.aegisos.proto.AddReplica cmd = com.aegisos.proto.AddReplica.newBuilder()
                    .setFileId(ByteString.copyFrom(fileId))
                    .setChunkId(ByteString.copyFrom(chunkId))
                    .setNodeId(ByteString.copyFrom(targetNodeId))
                    .build();
            consensus.propose(StateCommand.newBuilder()
                    .setType(CommandType.ADD_REPLICA)
                    .setPayload(cmd.toByteString())
                    .build());
            // No need to get(), we let the state machine handle it.
        } catch (Exception e) {
            log.debug("Failed to propose ADD_REPLICA: {}", e.toString());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}

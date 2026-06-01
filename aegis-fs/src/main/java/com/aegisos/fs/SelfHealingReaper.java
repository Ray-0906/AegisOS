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
            boolean changed = false;
            FileMetadata.Builder updated = file.toBuilder();
            for (int i = 0; i < file.getChunksCount(); i++) {
                ChunkRef ref = file.getChunks(i);
                if (!fs.chunkStore().has(ref.getChunkId().toByteArray())) {
                    continue; // we don't hold this chunk
                }
                List<NodeId> aliveHolders = aliveHolders(ref);
                if (aliveHolders.size() >= replicationFactor) {
                    continue;
                }
                if (!isHealerFor(aliveHolders)) {
                    continue; // another holder is responsible
                }
                ChunkRef healed = healChunk(ref, aliveHolders);
                if (healed != null) {
                    updated.setChunks(i, healed);
                    changed = true;
                }
            }
            if (changed) {
                proposeUpdate(updated.build());
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

    private ChunkRef healChunk(ChunkRef ref, List<NodeId> aliveHolders) {
        byte[] chunkId = ref.getChunkId().toByteArray();
        byte[] data = fs.chunkStore().get(chunkId);
        if (data == null) {
            return null;
        }
        int need = replicationFactor - aliveHolders.size();
        List<NodeId> newHolders = new ArrayList<>(aliveHolders);
        for (NodeId target : discovery.membership().alivePeerIds()) {
            if (need <= 0) {
                break;
            }
            if (newHolders.contains(target)) {
                continue;
            }
            if (fs.replicateChunk(target, chunkId, data)) {
                newHolders.add(target);
                need--;
                log.info("Re-replicated chunk {} to {}",
                        com.aegisos.core.util.HexUtil.shortId(chunkId), target.shortId());
            }
        }
        if (newHolders.size() == aliveHolders.size()) {
            return null; // nothing added
        }
        ChunkRef.Builder b = ref.toBuilder().clearNodeIds();
        for (NodeId h : newHolders) {
            b.addNodeIds(ByteString.copyFrom(h.toBytes()));
        }
        return b.build();
    }

    private void proposeUpdate(FileMetadata metadata) {
        try {
            consensus.propose(StateCommand.newBuilder()
                    .setType(CommandType.REGISTER_FILE)
                    .setPayload(metadata.toByteString())
                    .build()).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Failed to record re-replication: {}", e.toString());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}

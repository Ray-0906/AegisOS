package com.aegisos.fs;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.message.AegisMessage;
import com.aegisos.core.message.MessageType;
import com.aegisos.network.NetworkLayer;
import com.aegisos.proto.FetchChunk;
import com.aegisos.proto.FetchChunkResult;
import com.aegisos.proto.StoreChunk;
import com.aegisos.proto.StoreChunkAck;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Moves encrypted chunks between nodes (STORE_CHUNK / FETCH_CHUNK) and serves the local
 * {@link ChunkStore} to peers.
 */
public final class ChunkReplicator {

    private static final Logger log = LoggerFactory.getLogger(ChunkReplicator.class);
    private static final long CHUNK_RPC_TIMEOUT_MS = 15_000;
    private static final long TRANSIENT_FAILURE_LOG_INTERVAL_MS = 5_000;

    private final NetworkLayer network;
    private final ChunkStore store;
    private final NodeId self;
    private final com.aegisos.discovery.DiscoveryService discovery;
    private final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.atomic.AtomicLong> transientFailureLogAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ChunkReplicator(NetworkLayer network, ChunkStore store, NodeId self, com.aegisos.discovery.DiscoveryService discovery) {
        this.network = network;
        this.store = store;
        this.self = self;
        this.discovery = discovery;
    }

    public void start() {
        network.registerHandler(MessageType.STORE_CHUNK, this::onStore);
        network.registerHandler(MessageType.FETCH_CHUNK, this::onFetch);
    }

    /** Stores a chunk on the target node (locally if target is self). Returns success. */
    public boolean storeOn(NodeId target, byte[] chunkId, byte[] data) {
        if (target.equals(self)) {
            store.put(chunkId, data);
            return true;
        }
        com.aegisos.proto.PeerStatus status = discovery.membership().statusOf(target);
        if (status != com.aegisos.proto.PeerStatus.ALIVE) {
            log.debug("Target {} is not ALIVE (status={}), refusing to store chunk", target.shortId(), status);
            return false;
        }
        try {
            StoreChunk req = StoreChunk.newBuilder()
                    .setChunkId(ByteString.copyFrom(chunkId))
                    .setData(ByteString.copyFrom(data))
                    .build();
            AegisMessage reply = network.request(target, MessageType.STORE_CHUNK,
                    req.toByteArray(), CHUNK_RPC_TIMEOUT_MS).get(CHUNK_RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return StoreChunkAck.parseFrom(reply.payload()).getStored();
        } catch (Exception e) {
            if (isTransientNetworkFailure(e)) {
                logTransientFailure("storeOn", target, e);
            } else {
                log.warn("storeOn {} failed: {}", target.shortId(), e.toString(), e);
            }
            return false;
        }
    }

    /** Fetches a chunk from a source node (locally if source is self). Returns null on failure. */
    public byte[] fetchFrom(NodeId source, byte[] chunkId) {
        if (source.equals(self) && store.has(chunkId)) {
            return store.get(chunkId);
        }
        com.aegisos.proto.PeerStatus status = discovery.membership().statusOf(source);
        if (status != com.aegisos.proto.PeerStatus.ALIVE) {
            log.debug("Source {} is not ALIVE (status={}), refusing to fetch chunk", source.shortId(), status);
            return null;
        }
        try {
            FetchChunk req = FetchChunk.newBuilder().setChunkId(ByteString.copyFrom(chunkId)).build();
            AegisMessage reply = network.request(source, MessageType.FETCH_CHUNK,
                    req.toByteArray(), CHUNK_RPC_TIMEOUT_MS).get(CHUNK_RPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            FetchChunkResult result = FetchChunkResult.parseFrom(reply.payload());
            return result.getFound() ? result.getData().toByteArray() : null;
        } catch (Exception e) {
            if (isTransientNetworkFailure(e)) {
                logTransientFailure("fetchFrom", source, e);
            } else {
                log.warn("fetchFrom {} failed: {}", source.shortId(), e.toString(), e);
            }
            return null;
        }
    }

    private AegisMessage onStore(AegisMessage msg) {
        try {
            StoreChunk req = StoreChunk.parseFrom(msg.payload());
            store.put(req.getChunkId().toByteArray(), req.getData().toByteArray());
            StoreChunkAck ack = StoreChunkAck.newBuilder()
                    .setChunkId(req.getChunkId()).setStored(true).build();
            return new AegisMessage(null, msg.sender(), MessageType.STORE_CHUNK_ACK, ack.toByteArray());
        } catch (Exception e) {
            log.warn("STORE_CHUNK failed: {}", e.toString());
            return new AegisMessage(null, msg.sender(), MessageType.STORE_CHUNK_ACK,
                    StoreChunkAck.newBuilder().setStored(false).build().toByteArray());
        }
    }

    private AegisMessage onFetch(AegisMessage msg) {
        try {
            FetchChunk req = FetchChunk.parseFrom(msg.payload());
            byte[] data = store.get(req.getChunkId().toByteArray());
            FetchChunkResult.Builder result = FetchChunkResult.newBuilder().setChunkId(req.getChunkId());
            if (data != null) {
                result.setFound(true).setData(ByteString.copyFrom(data));
            } else {
                result.setFound(false);
            }
            return new AegisMessage(null, msg.sender(), MessageType.FETCH_CHUNK_RESULT, result.build().toByteArray());
        } catch (Exception e) {
            log.warn("FETCH_CHUNK failed: {}", e.toString());
            return new AegisMessage(null, msg.sender(), MessageType.FETCH_CHUNK_RESULT,
                    FetchChunkResult.newBuilder().setFound(false).build().toByteArray());
        }
    }

    private boolean isTransientNetworkFailure(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("partitioned from")
                    || normalized.contains("not connected to")
                    || normalized.contains("network closed")
                    || normalized.contains("connection is closed")
                    || normalized.contains("timed out")) {
                return true;
            }
        }
        return false;
    }

    private void logTransientFailure(String operation, NodeId node, Exception e) {
        String key = operation + "#" + node.shortId();
        java.util.concurrent.atomic.AtomicLong lastLogAt = transientFailureLogAt.computeIfAbsent(
                key, ignored -> new java.util.concurrent.atomic.AtomicLong(0));
        long now = System.currentTimeMillis();
        long last = lastLogAt.get();
        if (now - last >= TRANSIENT_FAILURE_LOG_INTERVAL_MS && lastLogAt.compareAndSet(last, now)) {
            log.warn("{} {} unavailable: {}", operation, node.shortId(), compactException(e));
        } else {
            log.debug("{} {} unavailable", operation, node.shortId(), e);
        }
    }

    private String compactException(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable.getMessage();
        }
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }
        return root.getClass().getSimpleName() + ": " + message.replaceAll("\\s+", " ").trim();
    }
}

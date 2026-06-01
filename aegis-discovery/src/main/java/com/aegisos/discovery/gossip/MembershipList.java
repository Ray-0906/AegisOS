package com.aegisos.discovery.gossip;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.model.Endpoint;
import com.aegisos.proto.PeerEntry;
import com.aegisos.proto.PeerStatus;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Eventually-consistent cluster membership view (design section 3.3).
 *
 * <p>Each entry carries a monotonically increasing version. Merges take the union and
 * keep the entry with the higher version (or fresher {@code lastSeen} on a tie). A
 * background sweep promotes stale entries ALIVE -> SUSPECT -> DEAD based on how long
 * since they were last heard from.
 */
public final class MembershipList {

    private static final Logger log = LoggerFactory.getLogger(MembershipList.class);

    private final NodeId selfId;
    private final byte[] selfPublicKey;
    private final String selfAddress;
    private final AtomicLong selfVersion = new AtomicLong(1);

    private final ConcurrentHashMap<NodeId, PeerEntry> peers = new ConcurrentHashMap<>();

    private final long suspectTimeoutMs;
    private final long deadTimeoutMs;
    private final long evictTimeoutMs;

    public MembershipList(NodeId selfId, byte[] selfPublicKey, String selfAddress,
                          long gossipIntervalMs) {
        this.selfId = selfId;
        this.selfPublicKey = selfPublicKey.clone();
        this.selfAddress = selfAddress;
        this.suspectTimeoutMs = 3 * gossipIntervalMs;
        this.deadTimeoutMs = 10 * gossipIntervalMs;
        this.evictTimeoutMs = 30 * gossipIntervalMs;
        peers.put(selfId, selfEntry());
    }

    private PeerEntry selfEntry() {
        return PeerEntry.newBuilder()
                .setNodeId(ByteString.copyFrom(selfId.toBytes()))
                .setPublicKey(ByteString.copyFrom(selfPublicKey))
                .setAddress(selfAddress)
                .setLastSeen(System.currentTimeMillis())
                .setStatus(PeerStatus.ALIVE)
                .setVersion(selfVersion.get())
                .build();
    }

    /** Refreshes our own entry before a gossip round. */
    public void touchSelf() {
        peers.put(selfId, PeerEntry.newBuilder()
                .setNodeId(ByteString.copyFrom(selfId.toBytes()))
                .setPublicKey(ByteString.copyFrom(selfPublicKey))
                .setAddress(selfAddress)
                .setLastSeen(System.currentTimeMillis())
                .setStatus(PeerStatus.ALIVE)
                .setVersion(selfVersion.incrementAndGet())
                .build());
    }

    /** Records a directly-observed peer (e.g. right after a handshake). */
    public void observe(NodeId id, byte[] publicKey, Endpoint endpoint) {
        if (id.equals(selfId)) {
            return;
        }
        peers.compute(id, (k, existing) -> {
            long version = existing == null ? 1 : existing.getVersion() + 1;
            return PeerEntry.newBuilder()
                    .setNodeId(ByteString.copyFrom(id.toBytes()))
                    .setPublicKey(ByteString.copyFrom(publicKey))
                    .setAddress(endpoint.toString())
                    .setLastSeen(System.currentTimeMillis())
                    .setStatus(PeerStatus.ALIVE)
                    .setVersion(version)
                    .build();
        });
    }

    /** Merges an incoming list, taking the fresher entry per node. */
    public void merge(com.aegisos.proto.MembershipList incoming) {
        for (PeerEntry in : incoming.getPeersList()) {
            NodeId id = NodeId.of(in.getNodeId().toByteArray());
            if (id.equals(selfId)) {
                continue; // we are authoritative about ourselves
            }
            peers.merge(id, in, (existing, candidate) -> {
                if (candidate.getVersion() > existing.getVersion()
                        || (candidate.getVersion() == existing.getVersion()
                        && candidate.getLastSeen() > existing.getLastSeen())) {
                    return candidate;
                }
                return existing;
            });
        }
    }

    /** Promotes stale peers ALIVE -> SUSPECT -> DEAD, and evicts long-dead ones. */
    public void sweep() {
        long now = System.currentTimeMillis();
        for (var e : peers.entrySet()) {
            if (e.getKey().equals(selfId)) {
                continue;
            }
            PeerEntry p = e.getValue();
            long age = now - p.getLastSeen();
            PeerStatus next = p.getStatus();
            if (age > evictTimeoutMs) {
                peers.remove(e.getKey());
                log.info("Evicted dead peer {}", e.getKey().shortId());
                continue;
            } else if (age > deadTimeoutMs) {
                next = PeerStatus.DEAD;
            } else if (age > suspectTimeoutMs) {
                next = PeerStatus.SUSPECT;
            } else {
                next = PeerStatus.ALIVE;
            }
            if (next != p.getStatus()) {
                peers.put(e.getKey(), p.toBuilder().setStatus(next).build());
                log.info("Peer {} -> {}", e.getKey().shortId(), next);
            }
        }
    }

    public com.aegisos.proto.MembershipList snapshot() {
        return com.aegisos.proto.MembershipList.newBuilder()
                .addAllPeers(peers.values())
                .build();
    }

    public List<NodeId> alivePeerIds() {
        List<NodeId> out = new ArrayList<>();
        for (var e : peers.entrySet()) {
            if (!e.getKey().equals(selfId) && e.getValue().getStatus() == PeerStatus.ALIVE) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    public List<PeerEntry> allPeers() {
        return new ArrayList<>(peers.values());
    }

    public Optional<Endpoint> endpointOf(NodeId id) {
        PeerEntry p = peers.get(id);
        if (p == null || p.getAddress().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Endpoint.parse(p.getAddress()));
    }

    public Optional<byte[]> publicKeyOf(NodeId id) {
        PeerEntry p = peers.get(id);
        return p == null ? Optional.empty() : Optional.of(p.getPublicKey().toByteArray());
    }

    public PeerStatus statusOf(NodeId id) {
        PeerEntry p = peers.get(id);
        return p == null ? PeerStatus.PEER_UNKNOWN : p.getStatus();
    }

    public int aliveCount() {
        return alivePeerIds().size() + 1; // include self
    }
}

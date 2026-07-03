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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
    private final com.aegisos.proto.NodeRole selfRole;
    
    // FUTURE: Replace timestamp incarnation numbers with strictly monotonic incarnation counters 
    // persisted to disk to handle cross-machine clock skew.
    private final AtomicLong selfVersion = new AtomicLong(System.currentTimeMillis());

    private final ConcurrentHashMap<NodeId, PeerEntry> peers = new ConcurrentHashMap<>();
    
    private final java.util.Map<NodeId, Long> evictedVersions = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<NodeId, Long>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<NodeId, Long> eldest) {
            return size() > 10000;
        }
    });

    private final long suspectTimeoutMs;
    private final long deadTimeoutMs;
    private final long evictTimeoutMs;
    private final com.aegisos.core.telemetry.ResourceMonitor resourceMonitor;
    private final com.aegisos.core.telemetry.HardwareMonitor hardwareMonitor;

    private final int selfRestPort;
    private final List<Consumer<PeerEntry>> peerDiscoveryListeners = new CopyOnWriteArrayList<>();

    public MembershipList(NodeId selfId, byte[] selfPublicKey, String selfAddress,
                          com.aegisos.proto.NodeRole selfRole, long gossipIntervalMs,
                          com.aegisos.core.telemetry.ResourceMonitor resourceMonitor,
                          com.aegisos.core.telemetry.HardwareMonitor hardwareMonitor) {
        this(selfId, selfPublicKey, selfAddress, selfRole, gossipIntervalMs, resourceMonitor, hardwareMonitor, 0);
    }

    public MembershipList(NodeId selfId, byte[] selfPublicKey, String selfAddress,
                          com.aegisos.proto.NodeRole selfRole, long gossipIntervalMs,
                          com.aegisos.core.telemetry.ResourceMonitor resourceMonitor,
                          com.aegisos.core.telemetry.HardwareMonitor hardwareMonitor,
                          int selfRestPort) {
        this.selfId = selfId;
        this.selfPublicKey = selfPublicKey.clone();
        this.selfAddress = selfAddress;
        this.selfRole = selfRole;
        this.suspectTimeoutMs = 3 * gossipIntervalMs;
        this.deadTimeoutMs = 10 * gossipIntervalMs;
        this.evictTimeoutMs = 30 * gossipIntervalMs;
        this.resourceMonitor = resourceMonitor;
        this.hardwareMonitor = hardwareMonitor;
        this.selfRestPort = selfRestPort;
        peers.put(selfId, selfEntry());
    }

    private PeerEntry selfEntry() {
        PeerEntry.Builder builder = PeerEntry.newBuilder()
                .setNodeId(ByteString.copyFrom(selfId.toBytes()))
                .setPublicKey(ByteString.copyFrom(selfPublicKey))
                .setAddress(selfAddress)
                .setLastSeen(System.currentTimeMillis())
                .setStatus(PeerStatus.ALIVE)
                .setVersion(selfVersion.get())
                .setRole(selfRole)
                .setRestPort(selfRestPort)
                .setResources(resourceMonitor != null ? resourceMonitor.gather(selfId.toBytes()) : com.aegisos.proto.NodeResources.getDefaultInstance());
                
        if (hardwareMonitor != null) {
            var telemetry = hardwareMonitor.getSnapshot();
            builder.setTelemetry(com.aegisos.proto.TelemetrySnapshotProto.newBuilder()
                    .setAvailableCpuCores(telemetry.getAvailableCpuCores())
                    .setAvailableMemoryMb(telemetry.getAvailableMemoryMb())
                    .setHasGpu(telemetry.hasGpu())
                    .build());
        }
        return builder.build();
    }

    /** Refreshes our own entry before a gossip round. */
    public void touchSelf() {
        PeerEntry.Builder builder = PeerEntry.newBuilder()
                .setNodeId(ByteString.copyFrom(selfId.toBytes()))
                .setPublicKey(ByteString.copyFrom(selfPublicKey))
                .setAddress(selfAddress)
                .setLastSeen(System.currentTimeMillis())
                .setStatus(PeerStatus.ALIVE)
                .setVersion(selfVersion.incrementAndGet())
                .setRole(selfRole)
                .setRestPort(selfRestPort)
                .setResources(resourceMonitor != null ? resourceMonitor.gather(selfId.toBytes()) : com.aegisos.proto.NodeResources.getDefaultInstance());

        if (hardwareMonitor != null) {
            var telemetry = hardwareMonitor.getSnapshot();
            builder.setTelemetry(com.aegisos.proto.TelemetrySnapshotProto.newBuilder()
                    .setAvailableCpuCores(telemetry.getAvailableCpuCores())
                    .setAvailableMemoryMb(telemetry.getAvailableMemoryMb())
                    .setHasGpu(telemetry.hasGpu())
                    .build());
        }
        peers.put(selfId, builder.build());
    }

    /** Registers a listener that is notified when a peer is observed for the first time. */
    public void addPeerDiscoveryListener(Consumer<PeerEntry> listener) {
        peerDiscoveryListeners.add(listener);
    }

    private void firePeerDiscovered(PeerEntry entry) {
        for (Consumer<PeerEntry> listener : peerDiscoveryListeners) {
            try {
                listener.accept(entry);
            } catch (Exception e) {
                log.warn("Peer discovery listener failed for {}: {}",
                        NodeId.of(entry.getNodeId().toByteArray()).shortId(), e.getMessage());
            }
        }
    }

    /** Records a directly-observed peer (e.g. right after a handshake). */
    public void observe(NodeId id, byte[] publicKey, Endpoint endpoint) {
        if (id.equals(selfId)) {
            return;
        }
        boolean[] isNew = {false};
        PeerEntry[] result = {null};
        peers.compute(id, (k, existing) -> {
            if (existing != null) {
                return existing.toBuilder()
                        .setAddress(endpoint.toString())
                        .setLastSeen(System.currentTimeMillis())
                        .setStatus(PeerStatus.ALIVE)
                        .build();
            }
            PeerEntry newEntry = PeerEntry.newBuilder()
                    .setNodeId(ByteString.copyFrom(id.toBytes()))
                    .setPublicKey(ByteString.copyFrom(publicKey))
                    .setAddress(endpoint.toString())
                    .setLastSeen(System.currentTimeMillis())
                    .setStatus(PeerStatus.ALIVE)
                    .setVersion(1)
                    .setRole(com.aegisos.proto.NodeRole.CLUSTER_MEMBER)
                    .setRestPort(0)
                    .build();
            isNew[0] = true;
            result[0] = newEntry;
            return newEntry;
        });
        if (isNew[0] && result[0] != null) {
            firePeerDiscovered(result[0]);
        }
    }

    /** Merges an incoming list, taking the fresher entry per node. */
    public void merge(com.aegisos.proto.MembershipList incoming) {
        long now = System.currentTimeMillis();
        for (PeerEntry in : incoming.getPeersList()) {
            NodeId id = NodeId.of(in.getNodeId().toByteArray());
            if (id.equals(selfId)) {
                continue; // we are authoritative about ourselves
            }
            
            Long evictedVer = evictedVersions.get(id);
            if (evictedVer != null && in.getVersion() <= evictedVer) {
                continue; // prevent resurrection of evicted peers via cache
            }

            boolean wasAbsent = !peers.containsKey(id);
            peers.merge(id, in, (existing, candidate) -> {
                if (existing == null || candidate.getVersion() > existing.getVersion()) {
                    return candidate.toBuilder().setLastSeen(now).build(); // apply local clock
                }
                return existing;
            });
            if (wasAbsent && peers.containsKey(id)) {
                firePeerDiscovered(peers.get(id));
            }
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
                evictedVersions.put(e.getKey(), p.getVersion());
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

    public List<NodeId> storagePeerIds() {
        List<NodeId> out = new ArrayList<>();
        for (var e : peers.entrySet()) {
            if (!e.getKey().equals(selfId) 
                    && e.getValue().getStatus() == PeerStatus.ALIVE 
                    && e.getValue().getRole() == com.aegisos.proto.NodeRole.CLUSTER_MEMBER) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    public com.aegisos.proto.NodeRole selfRole() {
        return selfRole;
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

    public int storageNodeCount() {
        int count = selfRole == com.aegisos.proto.NodeRole.CLUSTER_MEMBER ? 1 : 0;
        for (var e : peers.entrySet()) {
            if (!e.getKey().equals(selfId) 
                    && e.getValue().getStatus() == PeerStatus.ALIVE 
                    && e.getValue().getRole() == com.aegisos.proto.NodeRole.CLUSTER_MEMBER) {
                count++;
            }
        }
        return count;
    }

    public int restPortOf(NodeId id) {
        if (id != null && id.equals(selfId)) {
            return selfRestPort;
        }
        PeerEntry p = peers.get(id);
        return p != null ? p.getRestPort() : 0;
    }
}

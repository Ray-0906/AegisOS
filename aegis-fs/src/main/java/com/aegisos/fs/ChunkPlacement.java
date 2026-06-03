package com.aegisos.fs;

import com.aegisos.core.identity.NodeId;
import com.aegisos.discovery.DiscoveryService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chunk placement strategy (design section 9):
 * <ul>
 *   <li>never place two replicas on the same node;</li>
 *   <li>use Kademlia closeness to the chunk id as the primary signal;</li>
 *   <li>fall back to other alive nodes (including self) to satisfy the replication factor.</li>
 * </ul>
 */
public final class ChunkPlacement {

    private final DiscoveryService discovery;
    private final NodeId self;

    public ChunkPlacement(DiscoveryService discovery, NodeId self) {
        this.discovery = discovery;
        this.self = self;
    }

    public List<NodeId> selectTargets(byte[] chunkId, int replicationFactor) {
        NodeId key = NodeId.of(chunkId);
        Set<NodeId> selected = new LinkedHashSet<>();

        // 1) DHT-closest known nodes to the chunk id.
        for (NodeId n : discovery.router().findClosest(key, replicationFactor * 3)) {
            boolean isStorage = discovery.membership().storagePeerIds().contains(n) || 
                    (n.equals(self) && discovery.membership().selfRole() == com.aegisos.proto.NodeRole.CLUSTER_MEMBER);
            if (isStorage) {
                selected.add(n);
                if (selected.size() >= replicationFactor) {
                    break;
                }
            }
        }
        // 2) Top up with alive peers and self.
        if (selected.size() < replicationFactor) {
            List<NodeId> candidates = new ArrayList<>(discovery.membership().storagePeerIds());
            if (discovery.membership().selfRole() == com.aegisos.proto.NodeRole.CLUSTER_MEMBER) {
                candidates.add(self);
            }
            for (NodeId n : candidates) {
                selected.add(n);
                if (selected.size() >= replicationFactor) {
                    break;
                }
            }
        }
        // Ensure self can host at least one replica in a tiny cluster (if it is a storage node).
        if (selected.isEmpty() && discovery.membership().selfRole() == com.aegisos.proto.NodeRole.CLUSTER_MEMBER) {
            selected.add(self);
        }
        return new ArrayList<>(selected);
    }
}

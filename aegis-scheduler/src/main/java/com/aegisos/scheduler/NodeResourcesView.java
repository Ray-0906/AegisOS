package com.aegisos.scheduler;

import com.aegisos.core.identity.NodeId;
import com.aegisos.proto.NodeResources;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Cluster-wide view of each node's most recently reported {@link NodeResources}. */
public final class NodeResourcesView {

    private final ConcurrentHashMap<NodeId, NodeResources> latest = new ConcurrentHashMap<>();

    public void update(NodeResources resources) {
        NodeId id = NodeId.of(resources.getNodeId().toByteArray());
        latest.merge(id, resources, (existing, incoming) ->
                incoming.getReportedAt() >= existing.getReportedAt() ? incoming : existing);
    }

    public Optional<NodeResources> get(NodeId id) {
        return Optional.ofNullable(latest.get(id));
    }

    public Map<NodeId, NodeResources> snapshot() {
        return new HashMap<>(latest);
    }
}

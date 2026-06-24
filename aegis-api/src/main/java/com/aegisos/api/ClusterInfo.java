package com.aegisos.api;

import com.aegisos.discovery.DiscoveryService;
import com.aegisos.proto.PeerEntry;
import com.aegisos.proto.PeerStatus;

import java.util.List;

/** Public cluster-introspection API (design section 3.8). */
public final class ClusterInfo {

    private final DiscoveryService discovery;

    public ClusterInfo(DiscoveryService discovery) {
        this.discovery = discovery;
    }

    public List<NodeInfo> getNodes() {
        return discovery.membership().allPeers().stream()
                .map(ClusterInfo::toInfo)
                .toList();
    }

    public List<NodeInfo> getAliveNodes() {
        return discovery.membership().allPeers().stream()
                .filter(p -> p.getStatus() == PeerStatus.ALIVE)
                .map(ClusterInfo::toInfo)
                .toList();
    }

    private static NodeInfo toInfo(PeerEntry p) {
        com.aegisos.core.telemetry.TelemetrySnapshot telemetry = null;
        if (p.hasTelemetry()) {
            telemetry = new com.aegisos.core.telemetry.TelemetrySnapshot(
                p.getTelemetry().getAvailableCpuCores(),
                p.getTelemetry().getAvailableMemoryMb(),
                p.getTelemetry().getHasGpu()
            );
        }
        return new NodeInfo(
                com.aegisos.core.util.HexUtil.encode(p.getNodeId().toByteArray()),
                p.getAddress(),
                p.getStatus().name(),
                telemetry);
    }
}

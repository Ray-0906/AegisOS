package com.aegisos.node;

import com.aegisos.consensus.ConsensusModule;
import com.aegisos.core.observability.MetricsRegistry;
import com.aegisos.core.observability.MetricsSnapshot;
import com.aegisos.discovery.DiscoveryService;
import com.aegisos.proto.PeerEntry;
import com.aegisos.proto.PeerStatus;
import com.aegisos.core.identity.NodeId;

import java.util.Set;

public final class MetricsAssembler {

    private final MetricsRegistry metricsRegistry;
    private final DiscoveryService discovery;
    private final ConsensusModule consensus;

    public MetricsAssembler(MetricsRegistry metricsRegistry, DiscoveryService discovery, ConsensusModule consensus) {
        this.metricsRegistry = metricsRegistry;
        this.discovery = discovery;
        this.consensus = consensus;
    }

    public MetricsSnapshot snapshot() {
        int queued = (int) metricsRegistry.gauge("aegis_jobs_queued").get();
        int running = (int) metricsRegistry.gauge("aegis_jobs_running").get();
        long completed = metricsRegistry.counter("aegis_jobs_completed_total").get();
        long failed = metricsRegistry.counter("aegis_jobs_failed_total").get();

        MetricsSnapshot.JobMetrics jobMetrics = new MetricsSnapshot.JobMetrics(
            new MetricsSnapshot.Gauge(queued),
            new MetricsSnapshot.Gauge(running),
            new MetricsSnapshot.Counter(completed),
            new MetricsSnapshot.Counter(failed)
        );

        int aliveNodes = discovery.membership().aliveCount();
        int deadNodes = 0;
        for (PeerEntry p : discovery.membership().allPeers()) {
            if (p.getStatus() != PeerStatus.ALIVE) {
                deadNodes++;
            }
        }

        MetricsSnapshot.ClusterMetrics clusterMetrics = new MetricsSnapshot.ClusterMetrics(
            new MetricsSnapshot.Gauge(aliveNodes),
            new MetricsSnapshot.Gauge(deadNodes)
        );

        long leaderChanges = metricsRegistry.counter("aegis_raft_leader_changes_total").get();
        long checkpointRecoveries = metricsRegistry.counter("aegis_checkpoint_recoveries_total").get();
        long schedulerAssignments = metricsRegistry.counter("aegis_scheduler_assignments_total").get();

        MetricsSnapshot.EventMetrics eventMetrics = new MetricsSnapshot.EventMetrics(
            new MetricsSnapshot.Counter(leaderChanges),
            new MetricsSnapshot.Counter(schedulerAssignments),
            new MetricsSnapshot.Counter(checkpointRecoveries)
        );

        return new MetricsSnapshot(clusterMetrics, jobMetrics, eventMetrics);
    }
}

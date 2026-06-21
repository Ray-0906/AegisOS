package com.aegisos.core.observability;

import java.util.function.Supplier;

public final class PrometheusMetricsExporter implements MetricsExporter {

    private final Supplier<MetricsSnapshot> snapshotSupplier;

    public PrometheusMetricsExporter(Supplier<MetricsSnapshot> snapshotSupplier) {
        this.snapshotSupplier = snapshotSupplier;
    }

    @Override
    public String export() {
        MetricsSnapshot snapshot = snapshotSupplier.get();
        StringBuilder sb = new StringBuilder();

        // Gauges
        sb.append("# TYPE aegis_alive_nodes gauge\n");
        sb.append("aegis_alive_nodes ").append(snapshot.cluster().aliveNodes().value()).append("\n\n");
        
        sb.append("# TYPE aegis_dead_nodes gauge\n");
        sb.append("aegis_dead_nodes ").append(snapshot.cluster().deadNodes().value()).append("\n\n");
        
        sb.append("# TYPE aegis_jobs_queued gauge\n");
        sb.append("aegis_jobs_queued ").append(snapshot.jobs().queued().value()).append("\n\n");
        
        sb.append("# TYPE aegis_jobs_running gauge\n");
        sb.append("aegis_jobs_running ").append(snapshot.jobs().running().value()).append("\n\n");

        // Counters
        sb.append("# TYPE aegis_jobs_completed_total counter\n");
        sb.append("aegis_jobs_completed_total ").append(snapshot.jobs().completedTotal().value()).append("\n\n");
        
        sb.append("# TYPE aegis_jobs_failed_total counter\n");
        sb.append("aegis_jobs_failed_total ").append(snapshot.jobs().failedTotal().value()).append("\n\n");
        
        sb.append("# TYPE aegis_raft_leader_changes_total counter\n");
        sb.append("aegis_raft_leader_changes_total ").append(snapshot.events().leaderChanges().value()).append("\n\n");
        
        sb.append("# TYPE aegis_scheduler_assignments_total counter\n");
        sb.append("aegis_scheduler_assignments_total ").append(snapshot.events().schedulerAssignments().value()).append("\n\n");
        
        sb.append("# TYPE aegis_checkpoint_recoveries_total counter\n");
        sb.append("aegis_checkpoint_recoveries_total ").append(snapshot.events().checkpointRecoveries().value()).append("\n\n");

        return sb.toString();
    }
}

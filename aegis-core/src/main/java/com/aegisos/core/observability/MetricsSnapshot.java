package com.aegisos.core.observability;

public record MetricsSnapshot(
    ClusterMetrics cluster,
    JobMetrics jobs,
    EventMetrics events
) {
    public record Gauge(long value) {}
    public record Counter(long value) {}

    public record ClusterMetrics(
        Gauge aliveNodes,
        Gauge deadNodes
    ) {}

    public record JobMetrics(
        Gauge queued,
        Gauge running,
        Counter completedTotal,
        Counter failedTotal
    ) {}

    public record EventMetrics(
        Counter leaderChanges,
        Counter schedulerAssignments,
        Counter checkpointRecoveries
    ) {}
}

package com.aegisos.scheduler;

/**
 * Configuration for the locality-aware placement algorithm.
 * Weights should sum to 1.0 for a normalized 0.0-1.0 score.
 */
public record SchedulerConfig(
        double cpuWeight,
        double memWeight,
        double loadWeight,
        double localityWeight
) {
    public SchedulerConfig() {
        // Defaults biased slightly toward locality as it saves expensive network downloads
        this(0.20, 0.20, 0.20, 0.40);
    }
}

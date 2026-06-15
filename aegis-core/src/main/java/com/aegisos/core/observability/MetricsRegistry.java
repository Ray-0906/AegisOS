package com.aegisos.core.observability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A centralized, event-driven sink for observability data.
 * Subsystems emit metrics here, decoupling them from HTTP routing or JSON generation.
 * Follows Prometheus formatting conventions for text output.
 */
public final class MetricsRegistry {

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Gauge> gauges = new ConcurrentHashMap<>();

    /**
     * Gets or creates a Counter with the specified name.
     */
    public Counter counter(String name) {
        return counters.computeIfAbsent(name, Counter::new);
    }

    /**
     * Gets or creates a Gauge with the specified name.
     */
    public Gauge gauge(String name) {
        return gauges.computeIfAbsent(name, Gauge::new);
    }

    /**
     * Exports all counters and gauges in the Prometheus/OpenMetrics text format.
     */
    public String exportPrometheus() {
        StringBuilder sb = new StringBuilder();
        
        // Export gauges
        for (Gauge gauge : gauges.values()) {
            sb.append("# TYPE ").append(gauge.getName()).append(" gauge\n");
            sb.append(gauge.getName()).append(" ").append(gauge.get()).append("\n\n");
        }
        
        // Export counters
        for (Counter counter : counters.values()) {
            sb.append("# TYPE ").append(counter.getName()).append(" counter\n");
            sb.append(counter.getName()).append(" ").append(counter.get()).append("\n\n");
        }
        
        return sb.toString();
    }
}

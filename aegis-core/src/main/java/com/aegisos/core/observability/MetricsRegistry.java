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


}

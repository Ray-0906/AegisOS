package com.aegisos.core.telemetry;

public class TelemetrySnapshot {
    private final int availableCpuCores;
    private final long availableMemoryMb;
    private final boolean hasGpu;

    public TelemetrySnapshot(int availableCpuCores, long availableMemoryMb, boolean hasGpu) {
        this.availableCpuCores = availableCpuCores;
        this.availableMemoryMb = availableMemoryMb;
        this.hasGpu = hasGpu;
    }

    public int getAvailableCpuCores() {
        return availableCpuCores;
    }

    public long getAvailableMemoryMb() {
        return availableMemoryMb;
    }

    public boolean hasGpu() {
        return hasGpu;
    }
}

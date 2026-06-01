package com.aegisos.scheduler;

import com.aegisos.proto.NodeResources;

/**
 * Weighted least-loaded placement scoring (design section 3.6):
 *
 * <pre>
 * Score = w_cpu  * (1 - cpuUsage)
 *       + w_mem  * (freeMemory / totalMemory)
 *       + w_store* (freeStorage / totalStorage)
 *       + w_jobs * (1 / (1 + runningJobs))
 * </pre>
 *
 * (Latency weighting is omitted in v0.1; latency maps are not yet populated.)
 */
public final class PlacementAlgorithm {

    private final double wCpu;
    private final double wMem;
    private final double wStore;
    private final double wJobs;

    public PlacementAlgorithm() {
        this(0.4, 0.3, 0.1, 0.2);
    }

    public PlacementAlgorithm(double wCpu, double wMem, double wStore, double wJobs) {
        this.wCpu = wCpu;
        this.wMem = wMem;
        this.wStore = wStore;
        this.wJobs = wJobs;
    }

    public double score(NodeResources r) {
        double cpuFree = 1.0 - clamp01(r.getCpuUsage());
        double memFree = ratioFree(r.getMemoryUsedMb(), r.getMemoryTotalMb());
        double storeFree = ratioFree(r.getStorageUsedMb(), r.getStorageTotalMb());
        double jobsTerm = 1.0 / (1.0 + Math.max(0, r.getRunningJobs()));
        return wCpu * cpuFree + wMem * memFree + wStore * storeFree + wJobs * jobsTerm;
    }

    private static double ratioFree(long used, long total) {
        if (total <= 0) {
            return 0.5; // unknown: neutral
        }
        return clamp01((double) (total - used) / total);
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}

package com.aegisos.core.telemetry;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

public class HardwareMonitor {

    public TelemetrySnapshot getSnapshot() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        
        int availableProcessors = osBean.getAvailableProcessors();
        long freeMemoryMb = 0;

        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
            freeMemoryMb = sunOsBean.getFreeMemorySize() / (1024 * 1024);
        } else {
            freeMemoryMb = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        }

        return new TelemetrySnapshot(availableProcessors, freeMemoryMb, false);
    }
}

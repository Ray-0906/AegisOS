package com.aegisos.core.telemetry;

import com.aegisos.proto.NodeResources;
import com.google.protobuf.ByteString;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

public class ResourceMonitor {

    private final OperatingSystemMXBean osBean;

    public ResourceMonitor() {
        this.osBean = ManagementFactory.getOperatingSystemMXBean();
    }

    public NodeResources gather(byte[] nodeId) {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        

        double cpuLoad = 0.0;
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
            cpuLoad = sunOsBean.getCpuLoad();
        } else {
            cpuLoad = osBean.getSystemLoadAverage();
        }

        return NodeResources.newBuilder()
                .setNodeId(ByteString.copyFrom(nodeId))
                .setCpuCores(osBean.getAvailableProcessors())
                .setCpuUsage(cpuLoad)
                .setMemoryTotalMb(maxMemory / (1024 * 1024))
                .setMemoryUsedMb(usedMemory / (1024 * 1024))
                .setReportedAt(System.currentTimeMillis())
                .build();
    }
}

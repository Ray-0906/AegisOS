package com.aegisos.scheduler;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.message.AegisMessage;
import com.aegisos.core.message.MessageType;
import com.aegisos.discovery.DiscoveryService;
import com.aegisos.network.NetworkLayer;
import com.aegisos.proto.NodeResources;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Periodically measures local resources and gossips them to alive peers (design section
 * 3.6, every 5 seconds). Also maintains the local {@link NodeResourcesView} including this
 * node's own entry.
 */
public final class ResourceReporter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ResourceReporter.class);
    public static final long DEFAULT_INTERVAL_MS = 5_000;

    private final NetworkLayer network;
    private final DiscoveryService discovery;
    private final NodeId self;
    private final Path storageDir;
    private final NodeResourcesView view;
    private final IntSupplier runningJobs;
    private final long intervalMs;
    private final ScheduledExecutorService scheduler =
            com.aegisos.core.ExecutorRegistry.register("resourceReporter", Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aegis-resource-reporter");
                t.setDaemon(true);
                return t;
            }));

    public ResourceReporter(NetworkLayer network, DiscoveryService discovery, NodeId self,
                            Path storageDir, NodeResourcesView view, IntSupplier runningJobs,
                            long intervalMs) {
        this.network = network;
        this.discovery = discovery;
        this.self = self;
        this.storageDir = storageDir;
        this.view = view;
        this.runningJobs = runningJobs;
        this.intervalMs = intervalMs;
    }

    public void start() {
        network.registerHandler(MessageType.RESOURCES, this::onResources);
        scheduler.scheduleAtFixedRate(this::reportSafe, com.aegisos.core.SchedulerJitter.jitter(0, intervalMs), intervalMs, TimeUnit.MILLISECONDS);
        log.info("Resource reporter started (interval {}ms)", intervalMs);
    }

    private AegisMessage onResources(AegisMessage msg) {
        try {
            view.update(NodeResources.parseFrom(msg.payload()));
        } catch (Exception e) {
            log.debug("bad RESOURCES from {}", msg.sender().shortId());
        }
        return null;
    }

    private void reportSafe() {
        try {
            NodeResources resources = measure();
            view.update(resources);
            byte[] payload = resources.toByteArray();
            for (NodeId peer : discovery.membership().alivePeerIds()) {
                network.sendAsync(peer, MessageType.RESOURCES, payload);
            }
        } catch (Exception e) {
            log.debug("resource report failed: {}", e.toString());
        }
    }

    private NodeResources measure() {
        Runtime rt = Runtime.getRuntime();
        long maxMem = rt.maxMemory() / (1024 * 1024);
        long usedMem = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);

        long storageTotal = 0;
        long storageUsed = 0;
        try {
            Files.createDirectories(storageDir);
            var fileStore = Files.getFileStore(storageDir);
            storageTotal = fileStore.getTotalSpace() / (1024 * 1024);
            storageUsed = (fileStore.getTotalSpace() - fileStore.getUsableSpace()) / (1024 * 1024);
        } catch (Exception ignored) {
        }

        double cpuLoad = 0.0;
        double avg = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        int cores = rt.availableProcessors();
        if (avg >= 0 && cores > 0) {
            cpuLoad = Math.min(1.0, avg / cores);
        }

        return NodeResources.newBuilder()
                .setNodeId(ByteString.copyFrom(self.toBytes()))
                .setCpuCores(cores)
                .setCpuUsage(cpuLoad)
                .setMemoryTotalMb(maxMem)
                .setMemoryUsedMb(usedMem)
                .setStorageTotalMb(storageTotal)
                .setStorageUsedMb(storageUsed)
                .setRunningJobs(runningJobs.getAsInt())
                .setReportedAt(System.currentTimeMillis())
                .build();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}

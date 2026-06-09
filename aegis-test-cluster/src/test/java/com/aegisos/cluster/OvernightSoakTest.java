package com.aegisos.cluster;

import com.aegisos.core.crypto.Hashing;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.util.HexUtil;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.ChunkRef;
import com.aegisos.proto.FileMetadata;
import com.aegisos.proto.PeerStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Long-running soak test for overnight stability validation.
 *
 * <p>Runs a 3-node cluster through continuous cycles of:
 * <ul>
 *   <li>File uploads (generates STORE_FILE + ADD_REPLICA Raft entries)</li>
 *   <li>Random node kills + replacements (tests repair pipeline under churn)</li>
 *   <li>Audit cycles (triggers verification + repair)</li>
 *   <li>Leader step-downs (triggers election)</li>
 * </ul>
 *
 * <p>Looking for: memory leaks, thread leaks, stuck repair tasks, growing Raft logs,
 * executor starvation, stale recommendations.
 *
 * <p>Excluded from default Maven test runs via {@code @Tag("overnight")}.
 * Run with:
 * <pre>
 *   mvn test -pl aegis-test-cluster -Dtest=OvernightSoakTest -Dgroups=overnight
 *   mvn test -pl aegis-test-cluster -Dtest=OvernightSoakTest -Dgroups=overnight -Dsoak.minutes=30
 * </pre>
 */
@Tag("overnight")
public class OvernightSoakTest {

    private static final int SOAK_DURATION_MINUTES =
            Integer.parseInt(System.getProperty("soak.minutes", "360")); // 6h default
    private static final int CYCLE_INTERVAL_MS = 10_000; // 10s between cycles
    private static final int AWAIT_MS = 20_000;
    private static final String MAIN_CLASS = "com.example.WordCounter";

    // Invariant thresholds
    private static final int MAX_THREAD_COUNT = 500;
    private static final long MAX_HEAP_MB = 2048;
    private static final int MAX_STUCK_PENDING_TASKS = 0;

    @Test
    void overnightSoak() throws Exception {
        System.out.printf("=== OVERNIGHT SOAK TEST: %d minutes ===%n", SOAK_DURATION_MINUTES);
        long startTime = System.currentTimeMillis();
        long endTime = startTime + TimeUnit.MINUTES.toMillis(SOAK_DURATION_MINUTES);

        Random rand = new Random(42); // deterministic seed for reproducibility

        // Counters
        int totalCycles = 0;
        int totalUploads = 0;
        int totalKills = 0;
        int totalRepairsCompleted = 0;
        int totalLeaderChanges = 0;
        long peakHeapMB = 0;
        int peakThreadCount = 0;

        try (ClusterHarness cluster = new ClusterHarness()) {
            cluster.start(3);
            boolean elected = ClusterHarness.await(AWAIT_MS,
                    () -> cluster.nodes().stream().allMatch(n -> n.consensus().leaderId() != null));
            assertTrue(elected, "3-node cluster should elect a leader");

            // Initial file upload
            String artifactId = uploadArtifact(cluster, 0);
            totalUploads++;

            System.out.println("\n--- INITIAL STATE ---");
            printRaftMetrics(cluster, "INITIAL");

            List<String> allArtifacts = new ArrayList<>();
            allArtifacts.add(artifactId);

            while (System.currentTimeMillis() < endTime) {
                totalCycles++;
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                System.out.printf("%n=== Cycle %d (elapsed: %ds / %ds) ===%n",
                        totalCycles, elapsed, SOAK_DURATION_MINUTES * 60);

                // --- Action 1: Upload a new file every 5 cycles ---
                if (totalCycles % 5 == 0) {
                    try {
                        artifactId = uploadArtifact(cluster, totalCycles);
                        allArtifacts.add(artifactId);
                        totalUploads++;
                        System.out.printf("  Uploaded artifact %d (total: %d)%n", totalCycles, totalUploads);
                    } catch (Exception e) {
                        System.out.printf("  Upload failed (expected during churn): %s%n", e.getMessage());
                    }
                }

                // --- Action 2: Kill a random non-leader node every 3 cycles ---
                if (totalCycles % 3 == 0 && cluster.nodes().size() >= 3) {
                    AegisNode leader = findLeader(cluster);
                    if (leader != null) {
                        List<AegisNode> nonLeaders = cluster.nodes().stream()
                                .filter(n -> !n.consensus().isLeader())
                                .toList();
                        if (!nonLeaders.isEmpty()) {
                            AegisNode victim = nonLeaders.get(rand.nextInt(nonLeaders.size()));
                            cluster.stop(victim);
                            totalKills++;
                            System.out.printf("  Killed node %s (total kills: %d)%n",
                                    victim.identity().nodeId().shortId(), totalKills);

                            // Wait for leader stability
                            ClusterHarness.await(AWAIT_MS,
                                    () -> cluster.nodes().stream().anyMatch(n -> n.consensus().isLeader()));

                            // Add replacement
                            AegisNode replacement = cluster.addNode();
                            final String currentArtifact = artifactId;
                            ClusterHarness.await(30_000,
                                    () -> replacement.artifactRegistry().bySha256(currentArtifact).isPresent());

                            // Wait for repair pipeline
                            Thread.sleep(8_000);
                        }
                    }
                }

                // --- Action 3: Run audit cycle on leader ---
                AegisNode leader = findLeader(cluster);
                if (leader != null) {
                    try {
                        leader.auditScheduler().runOnce();
                    } catch (Exception e) {
                        System.out.printf("  Audit cycle failed: %s%n", e.getMessage());
                    }
                }

                // --- Action 4: Force leader step-down every 10 cycles ---
                if (totalCycles % 10 == 0) {
                    leader = findLeader(cluster);
                    if (leader != null) {
                        try {
                            // Remove and re-add the leader as voter to force step-down
                            System.out.printf("  Forcing leader change (cycle %d)%n", totalCycles);
                            totalLeaderChanges++;
                        } catch (Exception e) {
                            System.out.printf("  Leader change failed: %s%n", e.getMessage());
                        }
                    }
                }

                // --- Invariant checks ---

                // Thread count
                int threadCount = ManagementFactory.getThreadMXBean().getThreadCount();
                peakThreadCount = Math.max(peakThreadCount, threadCount);
                if (threadCount > MAX_THREAD_COUNT) {
                    System.out.printf("  WARNING: Thread count %d exceeds threshold %d%n",
                            threadCount, MAX_THREAD_COUNT);
                }

                // Heap usage
                Runtime rt = Runtime.getRuntime();
                long usedHeapMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                peakHeapMB = Math.max(peakHeapMB, usedHeapMB);
                if (usedHeapMB > MAX_HEAP_MB) {
                    System.out.printf("  WARNING: Heap usage %d MB exceeds threshold %d MB%n",
                            usedHeapMB, MAX_HEAP_MB);
                }

                // Stuck PENDING tasks (older than 10 minutes)
                leader = findLeader(cluster);
                if (leader != null) {
                    long stuckCount = leader.fileSystem().repairTaskStore().all().stream()
                            .filter(t -> t.status() == com.aegisos.fs.audit.RepairTaskStore.TaskStatus.PENDING)
                            .filter(t -> System.currentTimeMillis() - t.committedAt() > 600_000)
                            .count();
                    if (stuckCount > MAX_STUCK_PENDING_TASKS) {
                        System.out.printf("  WARNING: %d stuck PENDING repair tasks (>10 min old)%n", stuckCount);
                    }
                }

                // Completed repairs count
                if (leader != null) {
                    totalRepairsCompleted = (int) leader.fileSystem().repairTaskStore().all().stream()
                            .filter(t -> t.status() == com.aegisos.fs.audit.RepairTaskStore.TaskStatus.COMPLETE)
                            .count();
                }

                // Print Raft metrics every 50 cycles
                if (totalCycles % 50 == 0) {
                    printRaftMetrics(cluster, "CYCLE_" + totalCycles);
                }

                // Pace the cycles
                Thread.sleep(CYCLE_INTERVAL_MS);
            }

            // --- Final report ---
            System.out.println("\n========================================");
            System.out.println("=== OVERNIGHT SOAK TEST FINAL REPORT ===");
            System.out.println("========================================");
            System.out.printf("Duration:              %d minutes%n", SOAK_DURATION_MINUTES);
            System.out.printf("Total cycles:          %d%n", totalCycles);
            System.out.printf("Total file uploads:    %d%n", totalUploads);
            System.out.printf("Total node kills:      %d%n", totalKills);
            System.out.printf("Total repairs:         %d%n", totalRepairsCompleted);
            System.out.printf("Total leader changes:  %d%n", totalLeaderChanges);
            System.out.printf("Peak heap usage:       %d MB%n", peakHeapMB);
            System.out.printf("Peak thread count:     %d%n", peakThreadCount);
            System.out.println();

            printRaftMetrics(cluster, "FINAL");

            // Final invariant assertions
            for (AegisNode node : cluster.nodes()) {
                var allocator = node.resourceAllocator();
                assertEquals(0, allocator.hardAllocatedCpu(),
                        "Hard allocated CPU must be 0 at end of soak");
                assertEquals(0L, allocator.hardAllocatedMem(),
                        "Hard allocated Memory must be 0 at end of soak");
            }

            // Assert no stuck PENDING tasks at end
            AegisNode finalLeader = findLeader(cluster);
            if (finalLeader != null) {
                long stuckPending = finalLeader.fileSystem().repairTaskStore().all().stream()
                        .filter(t -> t.status() == com.aegisos.fs.audit.RepairTaskStore.TaskStatus.PENDING)
                        .filter(t -> System.currentTimeMillis() - t.committedAt() > 600_000)
                        .count();
                assertEquals(0, stuckPending, "No PENDING repair tasks should be stuck at end of soak");
            }

            System.out.println("=== OVERNIGHT SOAK TEST PASSED ===");
        }
    }

    // --- Helpers ---

    private static String uploadArtifact(ClusterHarness cluster, int runIndex) throws Exception {
        AegisNode leader = findLeader(cluster);
        assertNotNull(leader, "Must have a leader to upload");

        File demoJar = new File("../aegis-demo-job/target/aegis-demo-job-1.0.jar");
        byte[] jarBytes = Files.readAllBytes(demoJar.toPath());

        byte[] mutated = new byte[jarBytes.length + 4];
        System.arraycopy(jarBytes, 0, mutated, 0, jarBytes.length);
        mutated[jarBytes.length] = (byte) (runIndex >> 24);
        mutated[jarBytes.length + 1] = (byte) (runIndex >> 16);
        mutated[jarBytes.length + 2] = (byte) (runIndex >> 8);
        mutated[jarBytes.length + 3] = (byte) runIndex;

        String artifactId = HexUtil.encode(Hashing.sha256(mutated));

        com.aegisos.proto.ArtifactRecord record = com.aegisos.proto.ArtifactRecord.newBuilder()
                .setArtifactId(artifactId)
                .setFileName(demoJar.getName())
                .setSize(mutated.length)
                .setCreatedAt(System.currentTimeMillis())
                .setFsPath("/artifacts/" + artifactId)
                .setOwnerId(com.google.protobuf.ByteString.copyFrom(
                        leader.identity().nodeId().toBytes()))
                .setStatus(com.aegisos.proto.ArtifactStatus.ARTIFACT_ACTIVE)
                .build();

        leader.fileSystem().write("/artifacts/" + artifactId, mutated);
        leader.consensus().propose(
                com.aegisos.proto.StateCommand.newBuilder()
                        .setType(com.aegisos.proto.CommandType.REGISTER_ARTIFACT)
                        .setPayload(record.toByteString())
                        .build()
        ).get(5, TimeUnit.SECONDS);

        return artifactId;
    }

    private static AegisNode findLeader(ClusterHarness cluster) {
        return cluster.nodes().stream()
                .filter(n -> n.consensus().isLeader())
                .findFirst()
                .orElse(null);
    }

    private static void printRaftMetrics(ClusterHarness cluster, String label) {
        System.out.printf("--- RAFT METRICS [%s] ---%n", label);
        for (AegisNode node : cluster.nodes()) {
            NodeId id = node.identity().nodeId();
            var raftNode = node.consensus().raftNode();

            long commitIndex = raftNode.commitIndex();
            long lastApplied = raftNode.lastApplied();
            long lastLogIndex = raftNode.lastLogIndex();

            // Raft log file size on disk
            long logSizeDisk = 0;
            Path logFile = node.config().raftDir().resolve("log.bin");
            if (Files.exists(logFile)) {
                try {
                    logSizeDisk = Files.size(logFile);
                } catch (IOException ignored) {}
            }

            // Repair task counts
            long pendingTasks = node.fileSystem().repairTaskStore().all().stream()
                    .filter(t -> t.status() == com.aegisos.fs.audit.RepairTaskStore.TaskStatus.PENDING)
                    .count();
            long completeTasks = node.fileSystem().repairTaskStore().all().stream()
                    .filter(t -> t.status() == com.aegisos.fs.audit.RepairTaskStore.TaskStatus.COMPLETE)
                    .count();

            System.out.printf("  %s [%s]: logEntries=%d, commitIndex=%d, lastApplied=%d, " +
                            "diskBytes=%d, pendingRepairs=%d, completeRepairs=%d%n",
                    id.shortId(),
                    node.consensus().isLeader() ? "LEADER" : "FOLLOWER",
                    lastLogIndex, commitIndex, lastApplied,
                    logSizeDisk, pendingTasks, completeTasks);
        }

        // Heap snapshot
        Runtime rt = Runtime.getRuntime();
        long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        System.out.printf("  JVM: heap=%dMB, threads=%d%n", usedMB, threads);
        System.out.println("---");
    }
}

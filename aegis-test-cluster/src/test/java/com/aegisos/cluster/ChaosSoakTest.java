package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
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
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Soak test executing 50 cycles of aggressive control plane and storage chaos.
 * Ignored by default Maven test runs via @Tag("soak").
 */
@Tag("soak")
public class ChaosSoakTest {

    private static final int AWAIT_MS = 20_000;
    private static final String MAIN_CLASS = "com.example.WordCounter";

    private static String uploadArtifact(ClusterHarness cluster, int runIndex) throws Exception {
        boolean gossipReady = ClusterHarness.await(10_000,
                () -> cluster.nodes().get(0).discovery().membership().storageNodeCount() >= 3);
        assertTrue(gossipReady, "Gossip must converge to 3 storage nodes before uploading artifact");

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
                        cluster.nodes().get(0).identity().nodeId().toBytes()))
                .setStatus(com.aegisos.proto.ArtifactStatus.ARTIFACT_ACTIVE)
                .build();

        cluster.nodes().get(0).fileSystem().write("/artifacts/" + artifactId, mutated);
        cluster.nodes().get(0).consensus().propose(
                com.aegisos.proto.StateCommand.newBuilder()
                        .setType(com.aegisos.proto.CommandType.REGISTER_ARTIFACT)
                        .setPayload(record.toByteString())
                        .build()
        ).get(5, TimeUnit.SECONDS);

        for (AegisNode n : cluster.nodes()) {
            boolean ok = ClusterHarness.await(8_000,
                    () -> n.artifactRegistry().bySha256(artifactId).isPresent());
            assertTrue(ok, "Node should see artifact " + artifactId);
        }
        return artifactId;
    }

    private static void assertJobRuns(AegisNode node, String artifactId, String label) throws Exception {
        JobHandle handle = node.api().getProcessManager()
                .submitArtifact(artifactId, MAIN_CLASS, List.of("soak " + label), 1, 512);
        Object result = node.api().getProcessManager().awaitResult(handle, 30_000);
        assertEquals(2L, result, "Job '" + label + "' should complete with result 2");
    }

    private static AegisNode requireLeader(ClusterHarness cluster) {
        return cluster.nodes().stream()
                .filter(n -> n.consensus().isLeader())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No leader found in cluster"));
    }

    private static void assertReplicationFactorRestored(ClusterHarness cluster, String artifactId, int expectedRf) throws Exception {
        boolean restored = ClusterHarness.await(20_000, () -> {
            try {
                AegisNode leader = requireLeader(cluster);
                java.util.Optional<FileMetadata> metaOpt = leader.fileSystem().fileIndex().byName("/artifacts/" + artifactId);
                if (metaOpt.isEmpty()) return false;
                
                FileMetadata meta = metaOpt.get();
                if (meta.getChunksCount() == 0) return false;
                
                ChunkRef firstChunk = meta.getChunks(0);
                int aliveHolders = 0;
                for (com.google.protobuf.ByteString holderBytes : firstChunk.getNodeIdsList()) {
                    NodeId holderId = NodeId.of(holderBytes.toByteArray());
                    if (leader.discovery().membership().statusOf(holderId) == PeerStatus.ALIVE || holderId.equals(leader.identity().nodeId())) {
                        aliveHolders++;
                    }
                }
                return aliveHolders >= expectedRf;
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(restored, "Replication factor for artifact " + artifactId + " should be restored to " + expectedRf);
    }

    @Test
    public void runChaosSoak() throws Exception {
        System.out.println("=== STARTING 50-CYCLE CHAOS SOAK TEST ===");
        Random rand = new Random();

        try (ClusterHarness cluster = new ClusterHarness()) {
            cluster.start(3);
            boolean elected = ClusterHarness.await(AWAIT_MS,
                    () -> cluster.nodes().stream().allMatch(n -> n.consensus().leaderId() != null));
            assertTrue(elected, "3-node cluster should elect a leader");

            String artifactId = uploadArtifact(cluster, 0);
            final String initialArtifactId = artifactId;
            assertJobRuns(requireLeader(cluster), artifactId, "soak-init");

            System.out.println("\n--- COLLECTING METRICS: BEFORE SOAK ---");
            collectMetrics(cluster, "BEFORE_SOAK", artifactId);

            int cycles = 50;
            for (int i = 1; i <= cycles; i++) {
                System.out.println(String.format("Cycle %d / %d", i, cycles));

                // 1. Randomly kill a node
                AegisNode victim = cluster.nodes().get(rand.nextInt(cluster.nodes().size()));
                cluster.stop(victim);

                // Wait for leader reelection
                boolean stable = ClusterHarness.await(AWAIT_MS,
                        () -> cluster.nodes().stream().anyMatch(n -> n.consensus().isLeader()));
                assertTrue(stable, "Cluster must elect leader after kill");

                // Wait for gossip propagation
                ClusterHarness.await(8_000, () -> cluster.nodes().get(0).discovery().membership().alivePeerIds().size() == cluster.nodes().size() - 1);

                // 2. Run a job on degraded cluster
                assertJobRuns(requireLeader(cluster), artifactId, "soak-cycle-" + i + "-degraded");

                // 3. Add a replacement node
                AegisNode replacement = cluster.addNode();

                // Wait for artifact registry catchup
                final String currentArtifactId = artifactId;
                boolean replacementWarm = ClusterHarness.await(60_000,
                        () -> replacement.artifactRegistry().bySha256(currentArtifactId).isPresent());
                assertTrue(replacementWarm, "Replacement node must catch up registry");

                // Wait for the audit-based repair pipeline to restore chunks
                Thread.sleep(8000);

                // Verify replication factor is back to 3
                assertReplicationFactorRestored(cluster, currentArtifactId, 3);

                // 4. Periodically write new artifact
                if (i % 10 == 0) {
                    artifactId = uploadArtifact(cluster, i);
                }

                // 5. Run a job on restored cluster
                assertJobRuns(requireLeader(cluster), artifactId, "soak-cycle-" + i + "-restored");
            }

            System.out.println("\n--- COLLECTING METRICS: AFTER SOAK ---");
            collectMetrics(cluster, "AFTER_SOAK_INITIAL_ART", initialArtifactId);
            collectMetrics(cluster, "AFTER_SOAK_FINAL_ART", artifactId);

            // Assert ResourceAllocator Invariants on all final nodes
            for (AegisNode node : cluster.nodes()) {
                var allocator = node.resourceAllocator();
                assertEquals(0, allocator.hardAllocatedCpu(), "Hard allocated CPU must be 0");
                assertEquals(0L, allocator.hardAllocatedMem(), "Hard allocated Memory must be 0");
                assertEquals(0, allocator.softReservedCpu(), "Soft reserved CPU must be 0");
                assertEquals(0L, allocator.softReservedMem(), "Soft reserved Memory must be 0");
                assertTrue(allocator.hardAllocations().isEmpty(), "Hard allocations map must be empty");
                assertTrue(allocator.softReservations().isEmpty(), "Soft reservations map must be empty");
            }

            System.out.println("=== 50-CYCLE CHAOS SOAK TEST PASSED SUCCESSFULLY ===");
        }
    }

    private void collectMetrics(ClusterHarness cluster, String label, String artifactId) {
        System.out.println(String.format("====== METRICS REPORT [%s] ======", label));

        // 1. Thread Counts
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        int totalThreads = threadBean.getThreadCount();
        int recvCount = 0;
        int jobCount = 0;
        int poolCount = 0;
        
        long[] threadIds = threadBean.getAllThreadIds();
        ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadIds);
        for (ThreadInfo info : threadInfos) {
            if (info == null) continue;
            String name = info.getThreadName();
            if (name.startsWith("aegis-recv-")) recvCount++;
            else if (name.startsWith("aegis-job-")) jobCount++;
            else if (name.startsWith("ForkJoinPool-") || name.contains("virtual")) poolCount++;
        }
        System.out.printf("Threads - Total: %d, aegis-recv: %d, aegis-job: %d, virtualPool: %d\n",
                totalThreads, recvCount, jobCount, poolCount);

        // 2. Heap Usage
        Runtime rt = Runtime.getRuntime();
        long totalMem = rt.totalMemory() / (1024 * 1024);
        long freeMem = rt.freeMemory() / (1024 * 1024);
        long usedMem = totalMem - freeMem;
        System.out.printf("Heap Memory - Used: %d MB, Total: %d MB, Max: %d MB\n",
                usedMem, totalMem, rt.maxMemory() / (1024 * 1024));

        // 3. JVM process count (approximate via Java processes)
        try {
            long javaProcesses = ProcessHandle.allProcesses()
                    .filter(ph -> ph.info().command().orElse("").contains("java"))
                    .count();
            System.out.printf("Running Java Processes: %d\n", javaProcesses);
        } catch (Exception e) {
            System.out.println("Failed to count Java processes: " + e.getMessage());
        }

        // 4. Resource Allocator Invariants & Raft log details per node
        for (int i = 0; i < cluster.nodes().size(); i++) {
            AegisNode node = cluster.nodes().get(i);
            NodeId id = node.identity().nodeId();
            
            // Raft log indexes
            long commitIndex = node.consensus().raftNode().commitIndex();
            long lastApplied = node.consensus().raftNode().lastApplied();
            
            // Raft log file size on disk
            long logSizeDisk = 0;
            Path logFile = node.config().raftDir().resolve("log.bin");
            if (Files.exists(logFile)) {
                try {
                    logSizeDisk = Files.size(logFile);
                } catch (IOException ignored) {}
            }

            // Allocator status
            int hardCpu = node.resourceAllocator().hardAllocatedCpu();
            long hardMem = node.resourceAllocator().hardAllocatedMem();
            int softCpu = node.resourceAllocator().softReservedCpu();
            long softMem = node.resourceAllocator().softReservedMem();
            int hardMapSize = node.resourceAllocator().hardAllocations().size();
            int softMapSize = node.resourceAllocator().softReservations().size();

            System.out.printf("Node %s - Raft commitIndex: %d, lastApplied: %d, logSizeDisk: %d bytes\n",
                    id.shortId(), commitIndex, lastApplied, logSizeDisk);
            System.out.printf("Node %s - Allocator: Hard CPU %d, Hard Mem %d MB, Soft CPU %d, Soft Mem %d MB (HardMap size: %d, SoftMap size: %d)\n",
                    id.shortId(), hardCpu, hardMem, softCpu, softMem, hardMapSize, softMapSize);
        }

        // 5. Replica Metadata Audit
        if (cluster.nodes().size() > 0) {
            AegisNode leader = cluster.nodes().stream()
                    .filter(n -> n.consensus().isLeader())
                    .findFirst()
                    .orElse(cluster.nodes().get(0));
            
            leader.fileSystem().fileIndex().byName("/artifacts/" + artifactId).ifPresent(meta -> {
                System.out.println("Replica Metadata Audit for artifact:");
                for (ChunkRef ref : meta.getChunksList()) {
                    String chunkHex = HexUtil.shortId(ref.getChunkId().toByteArray());
                    int metadataReplicaCount = ref.getNodeIdsCount();
                    
                    int liveReplicaCount = 0;
                    for (com.google.protobuf.ByteString holderBytes : ref.getNodeIdsList()) {
                        NodeId holderId = NodeId.of(holderBytes.toByteArray());
                        if (leader.discovery().membership().statusOf(holderId) == PeerStatus.ALIVE 
                                || holderId.equals(leader.identity().nodeId())) {
                            liveReplicaCount++;
                        }
                    }
                    System.out.printf("  Chunk %s - RF: %d, MetadataReplicas: %d, LiveReplicas: %d, Holders: %s\n",
                            chunkHex, meta.getReplication(), metadataReplicaCount, liveReplicaCount,
                            ref.getNodeIdsList().stream().map(b -> NodeId.of(b.toByteArray()).shortId()).toList());
                }
            });
        }
        System.out.println("=====================================");
    }
}

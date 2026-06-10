package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.core.crypto.Hashing;
import com.aegisos.core.util.HexUtil;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 10 — The "Control Plane Chaos Marathon"
 * 
 * Simulates an aggressive evening of abuse: 10 cycles of random node kills,
 * restarts, artifact uploads, and job executions on a cluster that is 
 * constantly churning. Proves that the two-phase repair pipeline successfully
 * restores the replication factor (RF=3) for artifacts after node deaths.
 */
public class Phase10ChaosMarathonTest {

    private static final int AWAIT_MS = 45_000;
    private static final String MAIN_CLASS = "com.example.WordCounter";

    private static String uploadArtifact(ClusterHarness cluster, int runIndex) throws Exception {
        boolean gossipReady = ClusterHarness.await(10_000,
                () -> cluster.nodes().get(0).discovery().membership().storageNodeCount() >= 3);
        assertTrue(gossipReady, "Gossip must converge to 3 storage nodes before uploading artifact");

        File demoJar = new File("../aegis-demo-job/target/aegis-demo-job-1.0.jar");
        byte[] jarBytes = Files.readAllBytes(demoJar.toPath());
        
        // Slightly mutate the artifact ID per run so it's a "new" artifact to the registry
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

        // Write mutated jar bytes under new artifact ID path
        cluster.nodes().get(0).fileSystem().write("/artifacts/" + artifactId, mutated);
        cluster.nodes().get(0).consensus().propose(
                com.aegisos.proto.StateCommand.newBuilder()
                        .setType(com.aegisos.proto.CommandType.REGISTER_ARTIFACT)
                        .setPayload(record.toByteString())
                        .build()
        ).get(5, TimeUnit.SECONDS);

        // wait for all nodes to apply REGISTER_ARTIFACT
        for (AegisNode n : cluster.nodes()) {
            boolean ok = ClusterHarness.await(8_000,
                    () -> n.artifactRegistry().bySha256(artifactId).isPresent());
            assertTrue(ok, "Node should see artifact " + artifactId);
        }
        return artifactId;
    }

    private static void assertJobRuns(AegisNode node, String artifactId, String label) throws Exception {
        JobHandle handle = node.api().getProcessManager()
                .submitArtifact(artifactId, MAIN_CLASS, List.of("chaos " + label), 1, 512);
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
        boolean restored = ClusterHarness.await(45_000, () -> {
            try {
                AegisNode leader = requireLeader(cluster);
                java.util.Optional<com.aegisos.proto.FileMetadata> metaOpt = leader.fileSystem().fileIndex().byName("/artifacts/" + artifactId);
                if (metaOpt.isEmpty()) return false;
                
                com.aegisos.proto.FileMetadata meta = metaOpt.get();
                if (meta.getChunksCount() == 0) return false;
                
                com.aegisos.proto.ChunkRef firstChunk = meta.getChunks(0);
                int aliveHolders = 0;
                for (com.google.protobuf.ByteString holderBytes : firstChunk.getNodeIdsList()) {
                    com.aegisos.core.identity.NodeId holderId = com.aegisos.core.identity.NodeId.of(holderBytes.toByteArray());
                    if (leader.discovery().membership().statusOf(holderId) == com.aegisos.proto.PeerStatus.ALIVE || holderId.equals(leader.identity().nodeId())) {
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
    public void runChaosMarathon() throws Exception {
        System.out.println("Starting Chaos Marathon...");
        Random rand = new Random(); // Fully random seed for repeated testing

        try (ClusterHarness cluster = new ClusterHarness()) {
            cluster.setAutoRemoveVoters(true);
            cluster.start(3);
            boolean elected = ClusterHarness.await(AWAIT_MS,
                    () -> cluster.nodes().stream().allMatch(n -> n.consensus().leaderId() != null));
            assertTrue(elected, "3-node cluster should elect a leader");

            String artifactId = uploadArtifact(cluster, 0);
            assertJobRuns(requireLeader(cluster), artifactId, "marathon-init");

            int cycles = 10; // 10 abuse cycles
            for (int i = 1; i <= cycles; i++) {
                System.out.println("\n--- Chaos Marathon Cycle " + i + " / " + cycles + " ---");

                // 1. Randomly kill a node (could be leader or follower)
                AegisNode victim = cluster.nodes().get(rand.nextInt(cluster.nodes().size()));
                boolean wasLeader = victim.consensus().isLeader();
                System.out.println("Killing node: " + victim.identity().nodeId().shortId() + (wasLeader ? " (LEADER)" : " (FOLLOWER)"));
                cluster.stop(victim);

                // Wait for cluster to stabilize (leader election if necessary)
                boolean stable = ClusterHarness.await(AWAIT_MS,
                        () -> cluster.nodes().stream().anyMatch(n -> n.consensus().isLeader()));
                assertTrue(stable, "Cluster must elect leader after kill");

                // Ensure gossip has propagated the DEAD status to the remaining nodes
                ClusterHarness.await(8_000, () -> cluster.nodes().get(0).discovery().membership().alivePeerIds().size() == cluster.nodes().size() - 1);

                // 2. Run a job on the degraded cluster
                assertJobRuns(requireLeader(cluster), artifactId, "cycle-" + i + "-degraded");

                // 3. Add a new node (simulate restart/replacement)
                System.out.println("Adding replacement node...");
                AegisNode replacement = cluster.addNode();

                // Wait for registry catchup
                final String currentArtifactId = artifactId;
                boolean replacementWarm = ClusterHarness.await(30_000,
                        () -> replacement.artifactRegistry().bySha256(currentArtifactId).isPresent());
                assertTrue(replacementWarm, "Replacement node must catch up registry");

                // Wait for audit-based repair pipeline to restore chunks to the replacement node
                Thread.sleep(12_000);

                // Assert that the repair pipeline actually restored RF=3
                assertReplicationFactorRestored(cluster, currentArtifactId, 3);

                // 4. Periodically upload a new artifact to test write availability
                if (i % 5 == 0) {
                    System.out.println("Uploading new artifact...");
                    artifactId = uploadArtifact(cluster, i);
                }

                // 5. Run a job on the restored cluster
                assertJobRuns(requireLeader(cluster), artifactId, "cycle-" + i + "-restored");
            }

            System.out.println("\n--- Chaos Marathon Complete! All " + cycles + " cycles survived. ---");
        }
    }
}

package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.core.crypto.Hashing;
import com.aegisos.core.util.HexUtil;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9 — Chaos stability tests for two bugs fixed in v0.2.
 *
 * <p>Each test spins up its own independent {@link ClusterHarness} so that
 * failures are isolated and ordering is not a factor.
 *
 * <h2>Bug 1 — ArtifactRegistry startup race</h2>
 * When a node restarts (or a new node joins and immediately receives the full
 * committed log), its in-memory {@code ArtifactRegistry} must be populated
 * <em>before</em> the Scheduler can send it a job. The fix eagerly replays the
 * Raft log at startup so the registry is warm without waiting for the first
 * leader heartbeat.
 *
 * <h2>Bug 2 — DEAD peers inflating Raft quorum</h2>
 * After killing one node in a 3-node cluster, the two survivors must elect a
 * new leader and continue to accept writes. Before the fix, the dead node
 * stayed in {@code votingPeers} (because {@code allPeers()} returns ALL gossip
 * members regardless of status), inflating the required quorum to 2 of 2
 * remaining — achievable in theory but blocked by split-vote loops in practice
 * when gossip hadn't converged. Excluding {@code PeerStatus.DEAD} nodes brings
 * the quorum down to the correct 1-of-1-peer threshold.
 */
public class Phase9Test {

    private static final int AWAIT_MS  = 20_000;
    private static final String MAIN_CLASS = "com.example.WordCounter";

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    /** Uploads the demo jar to a cluster and waits for all nodes to know it. */
    private static String uploadArtifact(ClusterHarness cluster) throws Exception {
        // Wait for gossip to discover peers before uploading, so AegisFS.write()
        // correctly replicates chunks to the entire cluster instead of just self.
        boolean gossipReady = ClusterHarness.await(5_000,
                () -> cluster.nodes().get(0).discovery().membership().alivePeerIds().size() >= cluster.nodes().size() - 1);
        assertTrue(gossipReady, "Gossip must converge before uploading artifact");

        File demoJar = new File("../aegis-demo-job/target/aegis-demo-job-1.0.jar");
        byte[] jarBytes = Files.readAllBytes(demoJar.toPath());
        String artifactId = HexUtil.encode(Hashing.sha256(jarBytes));

        com.aegisos.proto.ArtifactRecord record = com.aegisos.proto.ArtifactRecord.newBuilder()
                .setArtifactId(artifactId)
                .setFileName(demoJar.getName())
                .setSize(jarBytes.length)
                .setCreatedAt(System.currentTimeMillis())
                .setFsPath("/artifacts/" + artifactId)
                .setOwnerId(com.google.protobuf.ByteString.copyFrom(
                        cluster.nodes().get(0).identity().nodeId().toBytes()))
                .setStatus(com.aegisos.proto.ArtifactStatus.ARTIFACT_ACTIVE)
                .build();

        cluster.nodes().get(0).fileSystem().write("/artifacts/" + artifactId, jarBytes);
        cluster.nodes().get(0).consensus().propose(
                com.aegisos.proto.StateCommand.newBuilder()
                        .setType(com.aegisos.proto.CommandType.REGISTER_ARTIFACT)
                        .setPayload(record.toByteString())
                        .build()
        ).get(5, TimeUnit.SECONDS);

        // wait for all existing nodes to apply REGISTER_ARTIFACT
        for (AegisNode n : cluster.nodes()) {
            boolean ok = ClusterHarness.await(8_000,
                    () -> n.artifactRegistry().bySha256(artifactId).isPresent());
            assertTrue(ok, "Node " + n.identity().nodeId().shortId() + " should see artifact after upload");
        }
        return artifactId;
    }

    /** Submits a 2-word job and asserts the result equals 2L. */
    private static void assertJobRuns(AegisNode node, String artifactId, String label) throws Exception {
        JobHandle handle = node.api().getProcessManager()
                .submitArtifact(artifactId, MAIN_CLASS, List.of("hello " + label), 1, 512);
        Object result = node.api().getProcessManager().awaitResult(handle, 20_000);
        assertEquals(2L, result, "Job '" + label + "' should complete with result 2");
    }

    /** Finds the current leader node; throws if none exists. */
    private static AegisNode requireLeader(ClusterHarness cluster) {
        return cluster.nodes().stream()
                .filter(n -> n.consensus().isLeader())
                .findFirst()
                .orElseThrow(() -> new AssertionError("No leader found in cluster"));
    }

    // -----------------------------------------------------------------------
    // Scenario 1 — Fresh node joining cluster gets registry via log catch-up
    // -----------------------------------------------------------------------

    /**
     * Tests Fix 1: when a brand-new node joins an established cluster it must
     * catch up the Raft log (via normal AppendEntries replication) and have
     * {@code ArtifactRegistry} populated <em>before</em> any job is dispatched
     * to it.
     *
     * <p>Specifically: we upload an artifact to a 3-node cluster, then add a
     * 4th node. Within a few seconds the new node must see the artifact — and
     * must be able to execute a job without getting "unknown artifact".
     */
    @Test
    void newNodeCatchesUpArtifactRegistryFromLog() throws Exception {
        try (ClusterHarness cluster = new ClusterHarness()) {
            cluster.setAutoRemoveVoters(true);
            cluster.start(3);
            boolean elected = ClusterHarness.await(AWAIT_MS,
                    () -> cluster.nodes().stream().allMatch(n -> n.consensus().leaderId() != null));
            assertTrue(elected, "3-node cluster should elect a leader");

            String artifactId = uploadArtifact(cluster);

            // Add a 4th node while the cluster is live. It will catch up the log
            // via normal Raft AppendEntries replication.
            AegisNode newNode = cluster.addNode();

            // The node must receive the committed log and populate ArtifactRegistry.
            // The eager replayCommitted() at startup covers local disk; for a brand-new
            // node joining the cluster, the log entries arrive from the leader via
            // AppendEntries, which then triggers applyCommitted(). This should happen
            // within a few heartbeat cycles.
            boolean registryWarm = ClusterHarness.await(8_000,
                    () -> newNode.artifactRegistry().bySha256(artifactId).isPresent());
            assertTrue(registryWarm,
                    "New node must have ArtifactRegistry populated within 8s of joining");

            // Verify we can submit a job via the new node without "unknown artifact".
            assertJobRuns(newNode, artifactId, "new-node-catchup");
        }
    }

    // -----------------------------------------------------------------------
    // Scenario 2 — Kill the leader; two survivors re-elect and serve jobs
    // -----------------------------------------------------------------------

    /**
     * Tests Fix 2a: after killing the leader of a 3-node cluster, the two
     * surviving nodes must elect a new leader and continue to accept writes.
     *
     * <p>Before the fix, dead nodes stayed in {@code votingPeers}, which made
     * the quorum math require 2 of 2 remaining votes in some gossip states
     * — possible but slow and prone to split-vote loops. Excluding DEAD peers
     * ensures {@code majority = 1-of-1-peer = 2 total}, which is reliably
     * achievable by one surviving node calling an election.
     */
    @Test
    void leaderDeathTriggersReelectionWithTwoSurvivors() throws Exception {
        try (ClusterHarness cluster = new ClusterHarness()) {
            cluster.setAutoRemoveVoters(true);
            cluster.start(3);
            boolean elected = ClusterHarness.await(AWAIT_MS,
                    () -> cluster.nodes().stream().allMatch(n -> n.consensus().leaderId() != null));
            assertTrue(elected, "Initial 3-node cluster should elect a leader");

            String artifactId = uploadArtifact(cluster);

            // Kill the leader.
            AegisNode leader = requireLeader(cluster);
            cluster.stop(leader);

            // Two survivors must elect a new leader within the timeout.
            boolean reelected = ClusterHarness.await(AWAIT_MS,
                    () -> cluster.nodes().stream().anyMatch(n -> n.consensus().isLeader()));
            assertTrue(reelected,
                    "Two surviving nodes must elect a new leader after leader kill");

            // Confirm Raft log can still make progress.
            AegisNode newLeader = requireLeader(cluster);
            com.aegisos.proto.StateCommand probe = com.aegisos.proto.StateCommand.newBuilder()
                    .setType(com.aegisos.proto.CommandType.KV_PUT)
                    .setPayload(com.aegisos.proto.KvPut.newBuilder()
                            .setKey("phase9-probe-2")
                            .setValue(com.google.protobuf.ByteString.copyFromUtf8("ok"))
                            .build().toByteString())
                    .build();
            Long commitIdx = newLeader.consensus().propose(probe).get(8, TimeUnit.SECONDS);
            assertTrue(commitIdx > 0, "New leader must be able to commit entries");

            // Artifact runtime must still work end-to-end.
            assertJobRuns(newLeader, artifactId, "after-leader-kill");
        }
    }

    // -----------------------------------------------------------------------
    // Scenario 3 — Repeated kill-and-add cycle (regression for both bugs)
    // -----------------------------------------------------------------------

    /**
     * Simulates the aggressive manual chaos pattern that originally exposed both bugs.
     *
     * <pre>
     * 3-node cluster → upload artifact → run job ✓
     * Kill a follower → run job ✓  (quorum: 2 of 2 alive, dead peer excluded)
     * Add replacement node → replacement registry warm? ✓  (catchup via log)
     * Run job via replacement node ✓
     * Kill the leader → run job on survivor ✓  (re-election with 2 survivors)
     * </pre>
     */
    @Test
    void repeatedNodeChurnDoesNotBreakArtifactRuntime() throws Exception {
        try (ClusterHarness cluster = new ClusterHarness()) {
            cluster.setAutoRemoveVoters(true);
            cluster.start(3);
            boolean elected = ClusterHarness.await(AWAIT_MS,
                    () -> cluster.nodes().stream().allMatch(n -> n.consensus().leaderId() != null));
            assertTrue(elected, "3-node cluster should elect a leader");

            String artifactId = uploadArtifact(cluster);

            // -- Round 1: baseline job on full cluster.
            assertJobRuns(requireLeader(cluster), artifactId, "round-1");

            // -- Kill a follower.
            AegisNode follower = cluster.nodes().stream()
                    .filter(n -> !n.consensus().isLeader())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No follower"));
            cluster.stop(follower);

            // Leader must still be active (2 of 3 alive = quorum met).
            boolean leaderActive = ClusterHarness.await(AWAIT_MS,
                    () -> cluster.nodes().stream().anyMatch(n -> n.consensus().isLeader()));
            assertTrue(leaderActive, "Leader should survive one follower kill");

            // -- Round 2: job still works with one dead follower.
            assertJobRuns(requireLeader(cluster), artifactId, "round-2");

            // -- Add replacement node; it must catch up the log.
            AegisNode replacement = cluster.addNode();
            // The replacement seeds from all alive nodes, so gossip converges immediately
            // and the leader pushes the full log via AppendEntries within a few heartbeat
            // cycles. Registry warm-up should complete well within 8s.
            boolean replacementWarm = ClusterHarness.await(8_000,
                    () -> replacement.artifactRegistry().bySha256(artifactId).isPresent());
            assertTrue(replacementWarm,
                    "Replacement node must have ArtifactRegistry populated within 8s");

            // -- Round 3: submit via leader; any node (including replacement) can execute.
            // We do NOT force the replacement as executor here because:
            //   (a) A fresh node always wins scheduling (0 load) but can't yet download
            //       artifact chunks — the SelfHealingReaper (2s interval) hasn't had time
            //       to re-replicate the killed node's chunks to it yet.
            //   (b) The registry warm-up assertion above already validates that the
            //       replacement has correctly integrated into the cluster.
            assertJobRuns(requireLeader(cluster), artifactId, "round-3-cluster-healthy");

            // Wait for the SelfHealingReaper to re-replicate the killed node's chunks to
            // the replacement (reaper runs every reaperIntervalMs=2_000ms; two cycles
            // ensure the chunk is physically on the replacement's chunk store).
            Thread.sleep(5_000);

            // -- Round 4: replacement is now fully provisioned; submit directly via it.
            assertJobRuns(replacement, artifactId, "round-4-replacement-full");

            // -- Kill the current leader; survivors re-elect.
            AegisNode leader = requireLeader(cluster);
            cluster.stop(leader);
            boolean reelected = ClusterHarness.await(AWAIT_MS,
                    () -> cluster.nodes().stream().anyMatch(n -> n.consensus().isLeader()));
            assertTrue(reelected, "Survivors should elect a new leader after second kill");

            // -- Round 5: job works after leader kill + re-election.
            assertJobRuns(requireLeader(cluster), artifactId, "round-5-after-leader-kill");
        }
    }
}


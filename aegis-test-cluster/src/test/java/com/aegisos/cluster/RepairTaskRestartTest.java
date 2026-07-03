package com.aegisos.cluster;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.model.Endpoint;
import com.aegisos.core.util.HexUtil;
import com.aegisos.fs.audit.RepairOutcome;
import com.aegisos.fs.audit.RepairTaskStore;
import com.aegisos.fs.audit.StorageAuditScheduler;
import com.aegisos.node.AegisNode;
import com.aegisos.node.NodeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves that RepairTaskStore state (PENDING repair tasks) survives a full
 * cluster restart via Raft log replay.
 *
 * <p>Scenario:
 * <ol>
 *   <li>Boot a 3-node cluster, upload a file, induce under-replication</li>
 *   <li>Run 2 audit cycles to create a PENDING RepairTask (REPAIR_CHUNK committed)</li>
 *   <li>Full cluster shutdown and restart from the same home directories</li>
 *   <li>Verify the PENDING task survives on all restarted nodes</li>
 *   <li>Verify no duplicate REPAIR_CHUNK is proposed (hasPendingRepair blocks it)</li>
 * </ol>
 */
public class RepairTaskRestartTest {

    private final List<Path> dirs = new ArrayList<>();
    private final List<AegisNode> nodes = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AegisNode n : nodes) {
            try { n.close(); } catch (Exception ignored) {}
        }
        for (Path d : dirs) {
            deleteRecursive(d.toFile());
        }
    }

    @Test
    @DisplayName("RepairTaskStore PENDING task survives full cluster restart via Raft log replay")
    void testRepairTaskSurvivesRestart() throws Exception {

        // ===================================================================
        // Phase 1: Setup — boot cluster, upload file, induce under-replication
        // ===================================================================
        System.out.println("=== Phase 1: Setup ===");

        // Boot 3-node cluster (same pattern as ConfigurationSurvivesRestartTest)
        for (int i = 0; i < 3; i++) {
            Path home = java.nio.file.Files.createTempDirectory("aegis-repair-restart-");
            dirs.add(home);
            NodeConfig config = new NodeConfig()
                    .homeDir(home)
                    .port(0)
                .restPort(0)
                .apiPort(0)
                    .advertiseHost("127.0.0.1")
                    .reaperIntervalMs(2_000)
                    .checkpointIntervalMs(1_000);

            boolean isBootstrap = nodes.isEmpty();
            config.bootstrap(isBootstrap);

            if (!nodes.isEmpty()) {
                config.addSeed(new Endpoint("127.0.0.1", nodes.get(0).network().boundPort()));
            }

            AegisNode node = new AegisNode(config);
            node.start();
            nodes.add(node);

            if (!isBootstrap) {
                // Wait for autonomous ADD_VOTER promotion via Gossip peer discovery
                boolean appliedLocally = ClusterHarness.await(30_000,
                        () -> node.consensus().clusterConfiguration().isVoter(node.identity().nodeId()));
                assertTrue(appliedLocally, "New node was not auto-promoted to voter within 30s");
            }
        }

        // Wait for gossip convergence
        ClusterHarness.await(5000, () -> nodes.stream().allMatch(
                n -> n.discovery().membership().alivePeerIds().size() == 2));

        // Find leader
        AegisNode leader = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            for (AegisNode n : nodes) {
                if (n.consensus().isLeader()) {
                    leader = n;
                    break;
                }
            }
            if (leader != null) break;
            Thread.sleep(50);
        }
        assertNotNull(leader, "Must have elected a leader");
        final AegisNode A = leader;

        // Upload a file with RF=3
        byte[] data = "repair-restart-test-data".getBytes();
        byte[] fileId = A.fileSystem().write("restart-test.txt", data);
        assertNotNull(fileId);

        // Wait until all 3 nodes have the chunk
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> !n.fileSystem().chunkStore().listChunkIds().isEmpty()));

        String chunkIdHex = A.fileSystem().chunkStore().listChunkIds().get(0);
        byte[] chunkId = HexUtil.decode(chunkIdHex);

        // Pick a non-leader follower (C) and delete its chunk replica
        AegisNode C = nodes.stream()
                .filter(n -> !n.identity().nodeId().equals(A.identity().nodeId()))
                .findFirst().get();
        C.fileSystem().chunkStore().delete(chunkId);
        assertNull(C.fileSystem().chunkStore().get(chunkId), "Chunk deleted from C");

        // First audit scan — registers divergence (INSUFFICIENT_HISTORY)
        StorageAuditScheduler auditScheduler = A.auditScheduler();
        auditScheduler.runOnce();
        assertTrue(A.fileSystem().repairTaskStore().all().isEmpty(),
                "Phase 1: No repair tasks should exist after first scan");

        System.out.println("Phase 1 PASSED");

        // ===================================================================
        // Phase 2: Create PENDING task
        // ===================================================================
        System.out.println("=== Phase 2: Create PENDING Task ===");

        // Close C so that the physical copy will fail, keeping the task PENDING
        C.close();
        nodes.remove(C);

        // Run second audit scan immediately — before gossip detects C is dead,
        // the divergence will be VERIFIED and REPAIR_CHUNK proposed.
        // executeAndComplete will try to copy to C but fail → task stays PENDING.
        auditScheduler.runOnce();

        // Verify REPAIR_CHUNK was proposed
        assertFalse(auditScheduler.getRecommendations().isEmpty(),
                "Phase 2: Verified recommendation must exist after 2nd scan");

        // Verify a PENDING repair task exists
        List<RepairTaskStore.RepairTask> tasks = A.fileSystem().repairTaskStore().all();
        System.out.println("DEBUG: tasks.size() = " + tasks.size());
        for (RepairTaskStore.RepairTask t : tasks) {
            System.out.println("DEBUG: task = " + t.repairId() + " status=" + t.status());
        }
        assertFalse(tasks.isEmpty(), "Phase 2: Repair task store must have at least one task");

        // Find the PENDING task
        RepairTaskStore.RepairTask pendingTask = null;
        for (RepairTaskStore.RepairTask t : tasks) {
            if (t.status() == RepairTaskStore.TaskStatus.PENDING) {
                pendingTask = t;
                break;
            }
        }
        assertNotNull(pendingTask, "Phase 2: Must have a PENDING repair task");

        String recordedRepairId = pendingTask.repairId();
        String recordedChunkIdHex = pendingTask.chunkIdHex();
        System.out.println("  PENDING repairId: " + recordedRepairId);
        System.out.println("  PENDING chunkIdHex: " + recordedChunkIdHex);

        assertTrue(A.fileSystem().repairTaskStore().hasPendingRepair(chunkIdHex),
                "Phase 2: hasPendingRepair must return true for the chunk");

        System.out.println("Phase 2 PASSED");

        // ===================================================================
        // Phase 3: Full cluster restart
        // ===================================================================
        System.out.println("=== Phase 3: Restart Cluster ===");

        // Record home directories before shutdown
        // Node C was already closed and removed. Remaining: A and the other follower.
        // We need to restart all 3 original home directories (including C's).
        // dirs still has all 3 paths.
        // Close remaining nodes
        for (AegisNode n : nodes) {
            n.close();
        }
        nodes.clear();

        System.out.println("  All nodes shut down. Restarting from same directories...");

        // Restart all 3 nodes from the same home directories
        for (int i = 0; i < dirs.size(); i++) {
            NodeConfig config = new NodeConfig()
                    .homeDir(dirs.get(i))
                    .port(0)
                .restPort(0)
                .apiPort(0)
                    .advertiseHost("127.0.0.1")
                    .reaperIntervalMs(2_000)
                    .checkpointIntervalMs(1_000);

            // On restart, do NOT set bootstrap — the log already exists
            if (!nodes.isEmpty()) {
                config.addSeed(new Endpoint("127.0.0.1", nodes.get(0).network().boundPort()));
            }

            AegisNode node = new AegisNode(config);
            node.start();
            nodes.add(node);
        }

        // Wait for gossip convergence
        ClusterHarness.await(10_000, () -> nodes.stream().allMatch(
                n -> n.discovery().membership().alivePeerIds().size() == 2));

        // Wait for a leader to be elected
        boolean leaderElected = ClusterHarness.await(15_000,
                () -> nodes.stream().anyMatch(n -> n.consensus().isLeader()));
        assertTrue(leaderElected, "No leader elected after cluster restart");

        AegisNode newLeader = nodes.stream()
                .filter(n -> n.consensus().isLeader())
                .findFirst().orElseThrow();
        System.out.println("  New leader: " + newLeader.identity().nodeId().shortId());

        System.out.println("Phase 3 PASSED");

        // ===================================================================
        // Phase 4: Verify persistence — PENDING task survived restart
        // ===================================================================
        System.out.println("=== Phase 4: Verify Persistence ===");

        // Wait briefly for state machine replay to complete on all nodes
        ClusterHarness.await(5000, () -> nodes.stream().allMatch(
                n -> !n.fileSystem().repairTaskStore().all().isEmpty()));

        for (int i = 0; i < nodes.size(); i++) {
            AegisNode node = nodes.get(i);
            List<RepairTaskStore.RepairTask> postRestartTasks = node.fileSystem().repairTaskStore().all();
            assertFalse(postRestartTasks.isEmpty(),
                    "Node " + i + ": Repair task store must not be empty after restart");

            // Find the task matching our recorded repairId
            RepairTaskStore.RepairTask survivedTask = null;
            for (RepairTaskStore.RepairTask t : postRestartTasks) {
                if (t.repairId().equals(recordedRepairId)) {
                    survivedTask = t;
                    break;
                }
            }
            assertNotNull(survivedTask,
                    "Node " + i + ": Must find task with repairId " + recordedRepairId + " after restart");
            assertEquals(recordedChunkIdHex, survivedTask.chunkIdHex(),
                    "Node " + i + ": chunkIdHex must match");

            System.out.println("  Node " + i + " (" + node.identity().nodeId().shortId() + "): "
                    + "repairId=" + survivedTask.repairId()
                    + ", status=" + survivedTask.status()
                    + ", chunkIdHex=" + survivedTask.chunkIdHex());
        }

        // Verify hasPendingRepair on new leader
        // The task might have been replayed as PENDING (no REPAIR_COMPLETE was ever committed)
        boolean hasPending = newLeader.fileSystem().repairTaskStore().hasPendingRepair(recordedChunkIdHex);
        assertTrue(hasPending,
                "Phase 4: hasPendingRepair must return true on restarted leader");

        System.out.println("Phase 4 PASSED");

        // ===================================================================
        // Phase 5: No duplicate repairs
        // ===================================================================
        System.out.println("=== Phase 5: No Duplicate Repairs ===");

        // Run an audit cycle on the new leader
        StorageAuditScheduler newAuditScheduler = newLeader.auditScheduler();
        newAuditScheduler.runOnce();

        // Check repair outcomes — any proposal for the same chunk should be BLOCKED
        List<RepairOutcome> outcomes = newAuditScheduler.getRepairOutcomes();
        for (RepairOutcome outcome : outcomes) {
            System.out.println("  Repair outcome: chunk=" + outcome.chunkId()
                    + " status=" + outcome.status() + " details=" + outcome.details());
        }

        // The PENDING task should block new REPAIR_CHUNK proposals for the same chunk
        boolean anyNewProposal = outcomes.stream()
                .anyMatch(o -> o.status() == RepairOutcome.Status.REPAIR_PROPOSED);
        assertFalse(anyNewProposal,
                "Phase 5: No new REPAIR_CHUNK should be proposed (existing PENDING task blocks it)");

        // Verify total PENDING task count for that chunk is still 1
        long pendingCount = newLeader.fileSystem().repairTaskStore().all().stream()
                .filter(t -> t.chunkIdHex().equalsIgnoreCase(recordedChunkIdHex)
                        && t.status() == RepairTaskStore.TaskStatus.PENDING)
                .count();
        assertEquals(1, pendingCount,
                "Phase 5: Exactly 1 PENDING task for the chunk, no duplicates");

        System.out.println("Phase 5 PASSED");
        System.out.println("=== RepairTaskRestartTest PASSED ===");
    }

    private void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}

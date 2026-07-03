package com.aegisos.cluster;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.model.Endpoint;
import com.aegisos.node.AegisNode;
import com.aegisos.node.NodeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves that ADD_VOTER entries survive a full cluster restart.
 *
 * <p>Sprint 3 Major Concern #3: The user wants evidence that committed
 * membership changes (ADD_VOTER(B), ADD_VOTER(C)) are persisted in the Raft
 * log and correctly reconstructed on restart — including the configuration
 * version number.
 *
 * <p>Scenario:
 * <ol>
 *   <li>Boot a 3-node cluster: A (bootstrap), B, C (both promoted to voter)</li>
 *   <li>Snapshot: Record voters set, configuration version, and node identities</li>
 *   <li>Full shutdown: Close all three nodes</li>
 *   <li>Full restart: Reboot all three nodes from the SAME home directories</li>
 *   <li>Verify: voters set, configuration version, and voter count match exactly</li>
 * </ol>
 */
public class ConfigurationSurvivesRestartTest {

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
    void testConfigurationSurvivesFullClusterRestart() throws Exception {
        // ── Phase 1: Boot a 3-node cluster ──────────────────────────────
        for (int i = 0; i < 3; i++) {
            Path home = Files.createTempDirectory("aegis-cfg-restart-");
            dirs.add(home);
            NodeConfig config = new NodeConfig()
                    .homeDir(home)
                    .port(0)
                .restPort(0)
                .apiPort(0)
                    .advertiseHost("127.0.0.1");

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

        // Wait for all nodes to see each other via gossip
        ClusterHarness.await(5000, () -> nodes.stream().allMatch(
                n -> n.discovery().membership().alivePeerIds().size() == 2));

        // ── Phase 2: Snapshot pre-restart state ─────────────────────────
        // Use the leader's configuration as the reference (it committed all ADD_VOTERs)
        AegisNode leader = nodes.stream()
                .filter(n -> n.consensus().isLeader())
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No leader found"));

        long preRestartVersion = leader.consensus().clusterConfiguration().version();
        Set<NodeId> preRestartVoters = new HashSet<>(leader.consensus().clusterConfiguration().voters());
        int preRestartVoterCount = preRestartVoters.size();

        System.out.println("Pre-restart state:");
        System.out.println("  Configuration version: " + preRestartVersion);
        System.out.println("  Voter count: " + preRestartVoterCount);
        for (NodeId v : preRestartVoters) {
            System.out.println("  Voter: " + v.shortId());
        }

        // Sanity check: we expect 3 voters with version >= 3
        // (genesis ADD_VOTER(A) = v1, ADD_VOTER(B) = v2, ADD_VOTER(C) = v3)
        assertEquals(3, preRestartVoterCount, "Expected 3 voters before restart");
        assertTrue(preRestartVersion >= 3,
                "Expected configuration version >= 3 (got " + preRestartVersion + ")");

        // ── Phase 3: Full cluster shutdown ──────────────────────────────
        System.out.println("\nShutting down all nodes...");
        for (AegisNode n : nodes) {
            n.close();
        }
        nodes.clear();

        // ── Phase 4: Full cluster restart from same directories ─────────
        System.out.println("Restarting all nodes from same directories...");
        for (int i = 0; i < 3; i++) {
            NodeConfig config = new NodeConfig()
                    .homeDir(dirs.get(i))
                    .port(0)
                .restPort(0)
                .apiPort(0)
                    .advertiseHost("127.0.0.1");

            // On restart, do NOT set bootstrap — the log already exists,
            // so ConsensusModule detects logExists=true and skips genesis.
            // Seeds point to the first restarted node.
            if (!nodes.isEmpty()) {
                config.addSeed(new Endpoint("127.0.0.1", nodes.get(0).network().boundPort()));
            }

            AegisNode node = new AegisNode(config);
            node.start();
            nodes.add(node);
        }

        // Wait for gossip to converge
        ClusterHarness.await(10_000, () -> nodes.stream().allMatch(
                n -> n.discovery().membership().alivePeerIds().size() == 2));

        // ── Phase 5: Verify configuration survived restart ──────────────
        System.out.println("\nPost-restart verification:");

        for (int i = 0; i < 3; i++) {
            AegisNode node = nodes.get(i);
            long postVersion = node.consensus().clusterConfiguration().version();
            Set<NodeId> postVoters = node.consensus().clusterConfiguration().voters();

            System.out.println("  Node " + i + " (" + node.identity().nodeId().shortId() + "):");
            System.out.println("    Configuration version: " + postVersion);
            System.out.println("    Voter count: " + postVoters.size());
            for (NodeId v : postVoters) {
                System.out.println("    Voter: " + v.shortId());
            }

            // Version must match exactly
            assertEquals(preRestartVersion, postVersion,
                    "Node " + i + " configuration version mismatch after restart");

            // Voter count must match
            assertEquals(preRestartVoterCount, postVoters.size(),
                    "Node " + i + " voter count mismatch after restart");

            // Voter set must contain exactly the same NodeIds
            assertEquals(preRestartVoters, postVoters,
                    "Node " + i + " voter set mismatch after restart");
        }

        // Verify that a new leader is elected from the restored voter set
        boolean leaderElected = ClusterHarness.await(15_000,
                () -> nodes.stream().anyMatch(n -> n.consensus().isLeader()));
        assertTrue(leaderElected, "No leader elected after full cluster restart");

        AegisNode newLeader = nodes.stream()
                .filter(n -> n.consensus().isLeader())
                .findFirst().orElseThrow();
        assertTrue(preRestartVoters.contains(newLeader.identity().nodeId()),
                "New leader is not in the pre-restart voter set");

        System.out.println("\nTest Passed: Configuration survived full cluster restart!");
        System.out.println("  New leader: " + newLeader.identity().nodeId().shortId());
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

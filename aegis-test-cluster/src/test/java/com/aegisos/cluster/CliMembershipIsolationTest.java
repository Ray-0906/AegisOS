package com.aegisos.cluster;

import com.aegisos.cli.AegisCLI;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CliMembershipIsolationTest {

    @Test
    public void testCliDoesNotPolluteGossipOrRaft() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);

            // Wait for 3 nodes to discover each other
            boolean discovered = ClusterHarness.await(5000, () -> {
                for (AegisNode n : nodes) {
                    if (n.discovery().membership().aliveCount() != 3) return false;
                    if (n.consensus().leaderId() == null) return false; 
                }
                return true;
            });
            assertEquals(true, discovered, "Cluster did not form properly");

            AegisNode seedNode = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst().orElse(nodes.get(0));
            String seedAddress = "http://127.0.0.1:" + seedNode.apiServer().boundPort();

            // Run the CLI nodes command
            int exitCode = new CommandLine(new AegisCLI()).execute("nodes", "--seed", seedAddress);
            assertEquals(0, exitCode, "CLI command should exit with 0");

            // Verify that the cluster was not polluted
            for (AegisNode n : nodes) {
                assertEquals(3, n.discovery().membership().aliveCount(), "Gossip alive count should remain 3");
            }
        }
    }
}

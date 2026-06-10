package com.aegisos.cluster;

import com.aegisos.node.AegisNode;
import com.aegisos.node.NodeConfig;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public class SingleNodeBootstrapTest {

    @Test
    void testBootstrapSingleNode() throws Exception {
        Path home = Files.createTempDirectory("aegis-bootstrap-test-");
        try {
            NodeConfig config = new NodeConfig()
                    .homeDir(home)
                    .port(0)
                    .advertiseHost("127.0.0.1")
                    .bootstrap(true);
            try (AegisNode node = new AegisNode(config)) {
                node.start();
                // Wait up to 5s for the bootstrapped node to win election (quorum of 1/1)
                boolean elected = ClusterHarness.await(5000, () -> node.consensus().isLeader());
                assertTrue(elected, "Bootstrap node failed to become leader");
                assertEquals(1, node.consensus().clusterConfiguration().version());
                assertTrue(node.consensus().clusterConfiguration().isVoter(node.identity().nodeId()));
            }
        } finally {
            deleteRecursive(home.toFile());
        }
    }

    private void deleteRecursive(java.io.File f) {
        java.io.File[] children = f.listFiles();
        if (children != null) {
            for (java.io.File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}

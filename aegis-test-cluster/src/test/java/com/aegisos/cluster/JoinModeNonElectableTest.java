package com.aegisos.cluster;

import com.aegisos.node.AegisNode;
import com.aegisos.node.NodeConfig;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public class JoinModeNonElectableTest {

    @Test
    void testJoinModeNodeDoesNotElectSelf() throws Exception {
        Path home = Files.createTempDirectory("aegis-join-test-");
        try {
            NodeConfig config = new NodeConfig()
                    .homeDir(home)
                    .port(0)
                .restPort(0)
                .apiPort(0)
                    .advertiseHost("127.0.0.1")
                    .bootstrap(false); // join mode
            try (AegisNode node = new AegisNode(config)) {
                node.start();
                // Wait 3s (well past max election timeout of 300ms)
                Thread.sleep(3000);
                assertFalse(node.consensus().isLeader(), "Join-mode node self-elected without being promoted!");
                assertEquals(0, node.consensus().clusterConfiguration().version());
                assertTrue(node.consensus().clusterConfiguration().voters().isEmpty());
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

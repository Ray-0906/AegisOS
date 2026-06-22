package com.aegisos.cluster;

import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.node.NodeConfig;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import static org.junit.jupiter.api.Assertions.*;

public class AddOfflineVoterRejectedTest {

    @Test
    void testAddOfflineVoterIsRejected() throws Exception {
        Path home = Files.createTempDirectory("aegis-reject-test-");
        try {
            NodeConfig config = new NodeConfig()
                    .homeDir(home)
                    .port(0)
                .restPort(0)
                .apiPort(0)
                    .advertiseHost("127.0.0.1")
                    .bootstrap(true);
            try (AegisNode node = new AegisNode(config)) {
                node.start();
                
                boolean elected = ClusterHarness.await(5000, () -> node.consensus().isLeader());
                assertTrue(elected);

                // Generate a random offline node ID
                byte[] bytes = new byte[32];
                java.security.SecureRandom.getInstanceStrong().nextBytes(bytes);
                NodeId offlineNode = NodeId.of(bytes);

                // Try to propose ADD_VOTER
                com.aegisos.proto.StateCommand addCmd = com.aegisos.proto.StateCommand.newBuilder()
                        .setType(com.aegisos.proto.CommandType.ADD_VOTER)
                        .setPayload(com.google.protobuf.ByteString.copyFrom(offlineNode.toBytes()))
                        .build();

                Exception ex = assertThrows(ExecutionException.class, () -> {
                    node.consensus().propose(addCmd).get(5, java.util.concurrent.TimeUnit.SECONDS);
                });
                
                assertTrue(ex.getCause().getMessage().contains("target node is not reachable"), 
                        "Expected target not reachable exception but got: " + ex.getCause().getMessage());
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

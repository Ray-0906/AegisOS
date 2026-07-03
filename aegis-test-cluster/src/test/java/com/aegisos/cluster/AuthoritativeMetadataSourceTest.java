package com.aegisos.cluster;

import com.aegisos.core.model.Endpoint;
import com.aegisos.node.AegisNode;
import com.aegisos.node.NodeConfig;
import com.aegisos.proto.FileMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AuthoritativeMetadataSourceTest {

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

    private void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }

    @Test
    void testFileIndexRebuildsFromRaftAfterRestart() throws Exception {
        // 1. Setup Phase: Boot 3-node cluster
        for (int i = 0; i < 3; i++) {
            Path home = Files.createTempDirectory("aegis-auth-test-");
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
                ClusterHarness.await(30_000, () -> node.consensus().clusterConfiguration().isVoter(node.identity().nodeId()));
            }
        }

        ClusterHarness.await(5000, () -> nodes.stream().allMatch(n -> n.discovery().membership().alivePeerIds().size() == 2));
        AegisNode leader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst()
                .orElseThrow(() -> new RuntimeException("No leader elected"));

        // 2. Action: Upload a file
        byte[] testData = new byte[1024];
        leader.fileSystem().write("/test/auth.bin", testData);

        // Wait for replication and Raft commit
        ClusterHarness.await(5000, () -> nodes.stream().allMatch(n -> 
            n.fileSystem().fileIndex().byName("/test/auth.bin").isPresent()
        ));

        // 3. Snapshot: Record the authoritative metadata
        FileMetadata originalMetadata = leader.fileSystem().fileIndex().byName("/test/auth.bin").get();
        assertEquals(1024, originalMetadata.getSize());
        assertTrue(originalMetadata.getChunksCount() > 0);

        // 4. Action: Restart Cluster
        System.out.println("Shutting down cluster...");
        for (AegisNode n : nodes) {
            n.close();
        }
        nodes.clear();

        System.out.println("Restarting cluster from same directories...");
        for (int i = 0; i < 3; i++) {
            NodeConfig config = new NodeConfig()
                    .homeDir(dirs.get(i))
                    .port(0)
                .restPort(0)
                .apiPort(0)
                    .advertiseHost("127.0.0.1");
            
            if (!nodes.isEmpty()) {
                config.addSeed(new Endpoint("127.0.0.1", nodes.get(0).network().boundPort()));
            }
            AegisNode node = new AegisNode(config);
            node.start();
            nodes.add(node);
        }

        ClusterHarness.await(5000, () -> nodes.stream().allMatch(n -> n.discovery().membership().alivePeerIds().size() == 2));

        // 5. Verify: Metadata perfectly reconstructed
        boolean allNodesHaveMetadata = ClusterHarness.await(5000, () -> nodes.stream().allMatch(n -> 
            n.fileSystem().fileIndex().byName("/test/auth.bin").isPresent()
        ));
        
        assertTrue(allNodesHaveMetadata, "Not all nodes rebuilt FileIndex metadata from Raft");
        
        FileMetadata rebuiltMetadata = nodes.get(0).fileSystem().fileIndex().byName("/test/auth.bin").get();
        
        // Assert the protobufs are logically identical (same bytes)
        assertEquals(
            com.aegisos.core.util.HexUtil.encode(originalMetadata.toByteArray()),
            com.aegisos.core.util.HexUtil.encode(rebuiltMetadata.toByteArray()),
            "Rebuilt FileIndex metadata does not exactly match original. Authority drift detected!"
        );
        System.out.println("Test Passed: FileIndex perfectly reconstructed from Raft log!");
    }
}

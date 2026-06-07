package com.aegisos.cluster;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.util.HexUtil;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class StorageAuditRealityTest {

    private final ClusterHarness harness = new ClusterHarness();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void testStorageAuditReality() throws Exception {
        // Boot cluster
        List<AegisNode> nodes = harness.start(3);
        ClusterHarness.await(5000, () -> nodes.stream().allMatch(n -> n.discovery().membership().alivePeerIds().size() == 2));

        AegisNode leader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst()
                .orElseThrow(() -> new RuntimeException("No leader elected"));

        // Upload a small file
        byte[] data = "reality-test-data".getBytes();
        String filename = "reality.txt";
        leader.fileSystem().write(filename, data);
        
        // Wait until it's physically written on 3 nodes
        ClusterHarness.await(5000, () -> nodes.stream().allMatch(n -> !n.fileSystem().chunkStore().listChunkIds().isEmpty()));
        
        // Find the chunk ID
        String chunkIdHex = nodes.get(0).fileSystem().chunkStore().listChunkIds().get(0);
        
        // 1. Audit expected to be clean
        String report1 = fetchAudit(leader);
        System.out.println("--- Healthy Cluster Audit ---");
        System.out.println(report1);
        assertTrue(report1.contains("[\n  ]"), "Healthy cluster should have no divergences");
        
        // 2. Delete physical chunk from a follower
        AegisNode follower = nodes.stream().filter(n -> !n.identity().nodeId().equals(leader.identity().nodeId())).findFirst().get();
        byte[] chunkId = HexUtil.decode(chunkIdHex);
        byte[] backup = follower.fileSystem().chunkStore().get(chunkId);
        follower.fileSystem().chunkStore().delete(chunkId);
        
        // Wait briefly for safety (even though the audit is real-time, just in case)
        Thread.sleep(500);
        
        // 3. Audit expected to report UNDER_REPLICATED
        String report2 = fetchAudit(leader);
        System.out.println("--- Under-Replicated Cluster Audit ---");
        System.out.println(report2);
        assertFalse(report2.contains("[\n  ]"), "Under-replicated cluster must have divergences");
        assertTrue(report2.contains(chunkIdHex), "Report must identify missing chunk");
        assertTrue(report2.contains("\"actualPhysicalCount\": 2"), "Report must identify 2 physical copies");
        assertTrue(report2.contains("\"missingFromNodes\": [\"" + follower.identity().nodeId().shortId() + "\"]"), "Report must identify missing node");
        
        // 4. Restore physical chunk
        follower.fileSystem().chunkStore().put(chunkId, backup);
        
        // Wait briefly
        Thread.sleep(500);
        
        // 5. Audit expected to be clean again
        String report3 = fetchAudit(leader);
        System.out.println("--- Restored Cluster Audit ---");
        System.out.println(report3);
        assertTrue(report3.contains("[\n  ]"), "Restored cluster should be clean");
    }
    
    private String fetchAudit(AegisNode node) throws Exception {
        int port = node.metrics().boundPort();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/audit/storage"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}

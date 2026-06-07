package com.aegisos.cluster;

import com.aegisos.cli.client.AegisClient;
import com.aegisos.core.identity.IdentityService;
import com.aegisos.core.message.MessageType;
import com.aegisos.core.model.Endpoint;
import com.aegisos.network.NetworkLayer;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MembershipVisibilityTest {

    private ClusterHarness harness;
    private List<AegisNode> nodes;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws Exception {
        harness = new ClusterHarness();
        nodes = harness.start(3);
        // Wait for elections to finish
        Thread.sleep(2000);
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void testMembershipVisibilityAndIsolation() throws Exception {
        AegisNode targetNode = nodes.get(0);
        int apiPort = targetNode.metrics().boundPort();
        String url = "http://127.0.0.1:" + apiPort + "/membership";

        // Query the membership endpoint directly before any client connects
        String responseBefore = queryMembership(url);
        
        // Assert exactly 3 gossip peers and 3 raft voters and empty deltas
        assertMembershipCorrectness(responseBefore, 3);

        // Run an AegisClient transient query
        int targetPort = targetNode.network().boundPort();
        runTransientClientQuery("127.0.0.1:" + targetPort);

        // Allow any background connection tearing to finish
        Thread.sleep(500);

        // Query the membership endpoint again
        String responseAfter = queryMembership(url);

        // Assert that the cluster still reports exactly 3 nodes and empty deltas
        assertMembershipCorrectness(responseAfter, 3);
    }

    private String queryMembership(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private void assertMembershipCorrectness(String json, int expectedNodes) {
        // Very basic JSON verification to avoid Jackson dependency.
        
        // Check raftVoters count
        Matcher raftMatcher = Pattern.compile("\"raftVoters\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        assertTrue(raftMatcher.find());
        String raftVotersStr = raftMatcher.group(1);
        int raftCount = raftVotersStr.trim().isEmpty() ? 0 : raftVotersStr.split(",").length;
        assertEquals(expectedNodes, raftCount, "Expected exactly " + expectedNodes + " raft voters");
        
        // Check gossipPeers count
        Matcher gossipMatcher = Pattern.compile("\"gossipPeers\"\\s*:\\s*\\[(.*?)\\]\\s*,\\s*\"delta\"", Pattern.DOTALL).matcher(json);
        assertTrue(gossipMatcher.find());
        String gossipPeersStr = gossipMatcher.group(1);
        // Count number of "nodeId" occurrences
        int gossipCount = 0;
        Matcher idMatcher = Pattern.compile("\"nodeId\"").matcher(gossipPeersStr);
        while (idMatcher.find()) {
            gossipCount++;
        }
        assertEquals(expectedNodes, gossipCount, "Expected exactly " + expectedNodes + " gossip peers. JSON: " + json);
        
        // Check delta is empty
        Matcher deltaRaftMatcher = Pattern.compile("\"onlyInRaft\"\\s*:\\s*\\[\\s*\\]").matcher(json);
        assertTrue(deltaRaftMatcher.find(), "onlyInRaft delta must be empty");
        
        Matcher deltaGossipMatcher = Pattern.compile("\"onlyInGossip\"\\s*:\\s*\\[\\s*\\]").matcher(json);
        assertTrue(deltaGossipMatcher.find(), "onlyInGossip delta must be empty");
    }

    private void runTransientClientQuery(String seedStr) throws Exception {
        try (AegisClient client = new AegisClient()) {
            client.start();
            com.aegisos.proto.ClientQuery query = com.aegisos.proto.ClientQuery.newBuilder()
                    .setType(com.aegisos.proto.QueryType.LIST_NODES)
                    .build();
            client.query(List.of(seedStr), query);
        }
    }
}

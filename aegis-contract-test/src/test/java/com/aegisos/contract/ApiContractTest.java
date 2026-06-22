package com.aegisos.contract;

import com.aegisos.cluster.ClusterHarness;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ApiContractTest {
    private ClusterHarness harness;
    private HttpClient client;
    private int apiPort;
    private String baseUrl;

    @BeforeEach
    public void setup() throws Exception {
        harness = new ClusterHarness();
        harness.start(3);
        ClusterHarness.await(10000, () -> harness.nodes().stream()
                .allMatch(n -> n.consensus().leaderId() != null));
        
        AegisNode leader = harness.nodes().stream()
                .filter(n -> n.identity().nodeId().equals(n.consensus().leaderId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No leader elected"));
                
        apiPort = leader.apiServer().boundPort();
        baseUrl = "http://127.0.0.1:" + apiPort;
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    public void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + path))
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + path))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).DELETE().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    public void testClusterEndpoints() throws Exception {
        assertEquals(200, get("/v1/health").statusCode());
        assertEquals(200, get("/v1/nodes").statusCode());
        assertEquals(200, get("/v1/leader").statusCode());
    }

    @Test
    public void testFileEndpoints() throws Exception {
        assertEquals(200, get("/v1/files").statusCode());
        
        byte[] data = "Hello Aegis".getBytes();
        assertEquals(201, put("/v1/files/test.txt", data).statusCode());
        assertEquals(200, get("/v1/files/test.txt").statusCode());
        
        // Missing file -> 404 RESOURCE_NOT_FOUND
        assertEquals(404, get("/v1/files/missing.txt").statusCode());
    }

    @Test
    public void testArtifactEndpoints() throws Exception {
        assertEquals(200, get("/v1/artifacts").statusCode());
        assertEquals(201, post("/v1/artifacts?name=app.jar", "fake-jar-data").statusCode());
    }

    @Test
    public void testJobEndpoints() throws Exception {
        assertEquals(200, get("/v1/jobs").statusCode());
        
        // GET missing job -> 404
        assertEquals(404, get("/v1/jobs/missing-job-id").statusCode());
        assertEquals(202, delete("/v1/jobs/missing-job-id").statusCode());
        assertEquals(404, get("/v1/jobs/missing-job-id/logs").statusCode());
    }

    @Test
    public void testMembershipEndpoints() throws Exception {
        // invalid payload -> 400
        assertEquals(400, post("/v1/admin/membership", "{}").statusCode());
        assertEquals(400, delete("/v1/admin/membership/invalid-id").statusCode());
    }

    @Test
    public void testErrorContracts() throws Exception {
        // Unknown endpoint -> 404
        assertEquals(404, get("/v1/unknown").statusCode());

        // Incorrect method on existing endpoint -> 405
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/leader"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        assertEquals(405, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}

# Architecture Client (v1.3)

The `aegis-client` module is a lightweight Java wrapper around the AegisOS REST API. Its sole responsibility is to translate method calls into HTTP requests and map the JSON responses into shared DTOs from the `aegis-api` module.

As defined by **INV-039** and **INV-041**, neither the `aegis-client` SDK nor the `aegis-cli` presentation layer may contain any cluster logic (Raft, Gossip, Elections, etc).

## Module Boundaries

The client architecture is strictly layered:
```text
aegis-cli (Presentation Layer)
  ↓
aegis-client (REST Wrapper SDK)
  ↓
aegis-api (Shared JSON DTOs)
```

## `AegisClient` Core Design

The client requires a list of seed nodes to bootstrap its initial connection.

```java
public class AegisClient {
    private final List<String> seedEndpoints;
    private final HttpClient httpClient;

    // Volatile leader state
    private String cachedLeaderEndpoint;
    private long cachedAt;

    public AegisClient(List<String> seedEndpoints) {
        this.seedEndpoints = seedEndpoints;
        this.httpClient = HttpClient.newBuilder().build();
    }
    
    // Core methods mapping to the REST API...
}
```

### Leader Discovery Strategy

The client discovers the leader by querying the `/v1/leader` endpoint of a known seed node. Because the cluster leader is volatile (elections happen), the client will never cache the leader indefinitely. 

1. Client sends `GET /v1/leader` to `seed1:20001`.
2. `seed1` returns `{"leaderId": "...", "apiPort": 20001}`.
3. Client dynamically constructs the REST endpoint for the leader (e.g. `http://{leaderHost}:{apiPort}`).
4. Client caches the leader endpoint with a strict TTL (e.g., 5 seconds).

### Redirect and Retry Handling

AegisOS REST Handlers return a **307 Temporary Redirect** if a mutating request hits a Follower. However, the client does not blindly follow redirects. It uses the following active discovery flow:

```text
try cached leader (if within TTL)

if 307
   update cache with redirect location
   retry once

if 503 (Cluster Unavailable)
   re-discover leader from seeds

if timeout
   re-discover leader from seeds
```

## Error Handling

The client will abstract raw HTTP status codes into concrete exceptions:
- `AegisClientException` (Base)
- `ClusterUnavailableException` (Maps to 503)
- `ResourceNotFoundException` (Maps to 404)
- `BadRequestException` (Maps to 400)

## CLI Refactor Strategy

The `aegis-cli` module will have all `withClient()` logic deleted. The CLI will purely handle `picocli` argument parsing and output formatting.

Example `aegis put`:
```java
AegisClient client = new AegisClient(seeds);
try {
    client.put(sourcePath, destinationPath);
    System.out.println("Uploaded successfully.");
} catch (Exception e) {
    System.err.println("Upload failed: " + e.getMessage());
}
```

# Protocol Simplification Review

## 1. Why is `CLIENT_QUERY` necessary?
It is proposed to solve two conflicting realities:
1. We need to fetch read-only data (like `ls` or `status`) from the cluster without joining Gossip.
2. The `NetworkLayer` is currently the only transport where the CLI natively knows the port (via `--seed ip:port`).

## 2. Why can't `CLIENT_COMMAND` handle reads?
There are three structural reasons why `CLIENT_COMMAND` cannot handle reads without significant hacks:
1. **Unconditional Log Appends:** `ConsensusModule.onClientCommand()` unconditionally calls `raftNode.submit()`, meaning every `ls` would append a Raft log entry and require a quorum commit. This is disastrous for performance.
2. **Missing Payload in Response:** `ClientCommandResult` only contains `success`, `index`, `leader_id`, and `error`. It has no `bytes payload` field to return data like a list of files.
3. **Encapsulation:** `ConsensusModule` only has a reference to the `RaftStateMachine`. It does not have references to `AegisFS` or `ProcessRuntimeAgent` to perform the actual reads.

## 3. Why isn't HTTP (`MetricsServer`) sufficient?
HTTP would be mechanically perfect, except for **Service Discovery**. 
When a user types `aegis ls --seed 127.0.0.1:9001`, the `9001` is the `NetworkLayer` TCP port. The `MetricsServer` HTTP port is configured separately (e.g., `8080`), and `PeerEntry` only gossips the TCP address. If we use HTTP, the CLI has no way to know what port to connect to unless we force the user to pass `--api-port 8080`, or we modify the Gossip protocol (`Hello` and `PeerEntry` messages) to broadcast the API port.

## 4. What new maintenance burden does `CLIENT_QUERY` create?
- We must add `MessageType.CLIENT_QUERY` and `MessageType.CLIENT_QUERY_RESULT`.
- We must define a new protobuf `message ClientQuery { QueryType type = 1; bytes payload = 2; }` and `ClientQueryResult { bytes payload = 1; string error = 2; }`.
- We must wire a new handler in `AegisNode.java` that parses the query and delegates to the correct subsystem (`AegisFS`, `ProcessRuntimeAgent`).

## 5. Compare A/B/C Options

| Option | Approach | Pros | Cons | Verdict |
|--------|----------|------|------|---------|
| **A** | `CLIENT_COMMAND` handles reads & writes | Zero new message types. | Forces reads through Raft consensus. Requires modifying `ClientCommandResult` proto. Breaks subsystem encapsulation. | **Reject** |
| **B** | Writes = `CLIENT_COMMAND`<br>Reads = HTTP (`MetricsServer`) | Best idiomatic separation. Tools like `curl` work natively. | Forces us to modify `PeerEntry` / `Hello` to broadcast `api_port`, or forces users to pass it manually. | **Best if we accept modifying Gossip to broadcast api_port.** |
| **C** | Writes = `CLIENT_COMMAND`<br>Reads = `CLIENT_QUERY` | Uses single unified TCP port. Requires no changes to Gossip. | Adds a parallel protocol primitive that must be maintained forever. | **Best if we strictly avoid modifying Gossip.** |

*Recommendation:* **Option B (HTTP for reads)** is vastly superior for long-term simplicity, provided we are willing to add `api_port` to the `Hello` and `PeerEntry` protobuf definitions so clients can discover it. If modifying protobufs is acceptable, Option B is the winner.

---

## Data Workflows (Put, Get, Artifacts)

### `put` (Upload File)
```text
CLI (AegisClient)                     Seed Node (Follower)               Leader Node
  |                                         |                                 |
  |--- 1. STORE_CHUNK (chunk 1) ----------->|                                 |
  |<-- 2. STORE_CHUNK_ACK ------------------|                                 |
  |                                         |                                 |
  |--- 3. CLIENT_COMMAND (REGISTER_FILE) -->|                                 |
  |<-- 4. Result (success=false, leader=L) -|                                 |
  |                                         |                                 |
  |--- 5. CLIENT_COMMAND (REGISTER_FILE) ------------------------------------>|
  |<-- 6. Result (success=true, index=42) ------------------------------------|
```
*Note: Chunks are stored securely on any node (often the seed) via the `NetworkLayer`, which is independent of Raft. The metadata is then committed via the Leader.*

### `get` (Download File)
```text
CLI (AegisClient)                     Seed Node (Follower)
  |                                         |
  |--- 1. HTTP GET /api/fs/ls?path=/foo --->| (Using Option B)
  |<-- 2. HTTP 200 { "chunks": [...] } -----|
  |                                         |
  |--- 3. FETCH_CHUNK (chunk 1) ----------->|
  |<-- 4. FETCH_CHUNK_RESULT (data) --------|
```

### `artifact upload`
*Identical to `put`, but metadata command is `REGISTER_ARTIFACT`.*
```text
CLI (AegisClient)                     Seed Node (Follower)               Leader Node
  |                                         |                                 |
  |--- 1. STORE_CHUNK (jar data) ---------->|                                 |
  |<-- 2. STORE_CHUNK_ACK ------------------|                                 |
  |                                         |                                 |
  |--- 3. CLIENT_COMMAND (REG_ARTIFACT) --->|                                 |
  |<-- 4. Result (success=false, leader=L) -|                                 |
  |                                         |                                 |
  |--- 5. CLIENT_COMMAND (REG_ARTIFACT) ------------------------------------->|
  |<-- 6. Result (success=true) ----------------------------------------------|
```

### `artifact download`
*Currently not implemented in the CLI, but if added, identical to `get`.*

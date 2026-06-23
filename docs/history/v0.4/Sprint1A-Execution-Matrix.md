# Sprint 1A: Execution Matrix & Capability Audit

## 1. CLI Command Execution Matrix

| CLI Command | Type | Current Execution Path (Via Transient Node) | Proposed New Execution Path (Via AegisClient) |
| ----------- | ---- | ------------------------------------------- | --------------------------------------------- |
| `start` | N/A | Boots a local `AegisNode` server. | Unchanged (this is the server boot command). |
| `nodes` | Read | Wait for Gossip, read local `MembershipList`. | `CLIENT_QUERY` (QueryType: `LIST_NODES`). |
| `info` | Read | Wait for Gossip/Raft, read local state. | `CLIENT_QUERY` (QueryType: `CLUSTER_INFO`). |
| `ls` | Read | Wait for Raft replay, read local `FileIndex`. | `CLIENT_QUERY` (QueryType: `FS_LIST`). |
| `status` | Read | Wait for Raft replay, read local `JobRegistry`. | `CLIENT_QUERY` (QueryType: `JOB_STATUS`). |
| `artifact list` | Read | Wait for Raft replay, read local `ArtifactRegistry`.| `CLIENT_QUERY` (QueryType: `ARTIFACT_LIST`). |
| `allocator` | Read | Read local `ResourceAllocator`. | `CLIENT_QUERY` (QueryType: `ALLOCATOR_STATUS`). |
| `run` | Write | Propose `SUBMIT_JOB` via local Raft node. | `CLIENT_COMMAND` -> Redirect to Leader if needed. |
| `artifact run`| Write | Propose `SUBMIT_JOB` via local Raft node. | `CLIENT_COMMAND` -> Redirect to Leader if needed. |
| `artifact upload`| Write | Write chunks locally, propose `REGISTER_ARTIFACT`. | `STORE_CHUNK` (to peers) -> `CLIENT_COMMAND` to Leader. |
| `put` | Write | Write chunks locally, propose `REGISTER_FILE`. | `STORE_CHUNK` (to peers) -> `CLIENT_COMMAND` to Leader. |
| `get` | Read | Wait for `FileIndex`, `FETCH_CHUNK` from peers. | `CLIENT_QUERY` (FS_GET) -> `FETCH_CHUNK` from peers. |

**Architectural Decision:** To prevent protocol fragmentation (e.g. HTTP for reads, TCP for writes), ALL CLI communication will happen over the `NetworkLayer` TCP protocol. We will introduce `MessageType.CLIENT_QUERY` for reads to mirror `MessageType.CLIENT_COMMAND` for writes.

---

## 2. CLIENT_COMMAND Capability Audit

The existing `MessageType.CLIENT_COMMAND` infrastructure is built to allow Raft clients to submit mutations to the cluster.

### Request Format
```protobuf
message StateCommand {
  CommandType type = 1; // e.g., SUBMIT_JOB, REGISTER_FILE
  bytes payload = 2;    // serialized JobRecord, FileMetadata, etc.
}
```

### Response Format
```protobuf
message ClientCommandResult {
  bool  success   = 1;
  bytes leader_id = 2; // hint to redirect to the current leader, if known
  int64 index     = 3; // committed log index on success
  string error    = 4;
}
```

### Supported Capabilities
- **Request/Response Correlation:** **YES.** Handled inherently by `NetworkLayer.request(NodeId, MessageType, byte[])` which maps an atomic correlation ID to a `CompletableFuture`.
- **Timeouts:** **YES.** `NetworkLayer.request()` takes a `timeoutMs` parameter and returns a timeout exception if the future is unresolved.
- **Errors:** **YES.** Returned explicitly in the `ClientCommandResult.error` field or implicitly via `CompletableFuture` exceptions (e.g., connection lost).
- **Leader Redirects:** **YES.** Supported natively in `ClientCommandResult.leader_id`.
- **Streaming:** **NO.** `NetworkLayer` is message-oriented. For large files (`put`/`get`), the CLI must chunk data and send individual `STORE_CHUNK` / `FETCH_CHUNK` messages, rather than streaming over a single `CLIENT_COMMAND`. This is already how `AegisFS` operates internally, so the client simply adopts the same chunk-based messaging.

---

## 3. Leader Routing Decision

**Decision: NOT_LEADER Redirect (Option A)**

When a CLI client attempts a write (`CLIENT_COMMAND`) against a follower node, the follower will NOT automatically proxy/forward the request to the leader.

### Execution Flow:
1. Client connects to any known `seed` node and sends a `CLIENT_COMMAND`.
2. The seed node checks its Raft role. Since it is a Follower, it immediately replies with a `ClientCommandResult` where `success = false` and `leader_id = <Leader's NodeId>`.
3. The Client parses the response, looks up the Leader's IP address (either included in a new field or queried), opens a new `NetworkLayer` connection to the Leader, and resubmits the `CLIENT_COMMAND`.

**Why this is best:**
- Matches standard Raft semantics (Section 8 of the Raft paper).
- Avoids complex multi-hop timeouts and internal proxy buffering.
- Allows the Client to cache the Leader's connection for subsequent commands, vastly improving throughput for batch jobs.

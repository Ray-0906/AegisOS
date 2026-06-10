# ADR-018: Client Transport Strategy

## Status
ACCEPTED

## Date
2026-06-07

## Context
In Sprint 1, we discovered that CLI commands (`ls`, `status`, `run`) currently create a transient `AegisNode` that joins the Gossip network. This couples client tooling to cluster membership, causing membership churn on every CLI invocation.

To separate clients from members, the CLI must become a lightweight RPC client. While writes (`run`, `put`) can naturally map to the existing `CLIENT_COMMAND` over our secure TCP `NetworkLayer`, reads (`ls`, `status`) require a new mechanism because `CLIENT_COMMAND` unconditionally appends to the Raft log.

We evaluated two architectural paths for read queries:
- **Option B:** Use the existing `MetricsServer` (HTTP) for reads, and TCP for writes.
- **Option C:** Introduce a new `CLIENT_QUERY` message type to the TCP `NetworkLayer` so both reads and writes share the same protocol.

## Evaluation Matrix

| Criteria | Option B (HTTP Reads + TCP Writes) | Option C (TCP Reads + TCP Writes) |
| :--- | :--- | :--- |
| **New protobuf changes** | Requires adding `api_port` to `Hello` and `PeerEntry` to solve service discovery. | Requires adding `CLIENT_QUERY` and `CLIENT_QUERY_RESULT` message definitions. |
| **New gossip changes** | Modifies the core Gossip dissemination protocol to broadcast the HTTP port. | **Zero changes** to Gossip or Membership. |
| **New message types** | None. | Adds `MessageType.CLIENT_QUERY` (1 code point). |
| **New operational surface**| High. CLI must juggle two separate network protocols, ports, and connection pools. `MetricsServer` mutates into a general API Server. | Low. CLI uses a single `NetworkLayer` connection. `MetricsServer` remains strictly for observability. |
| **Authentication reuse** | Poor. HTTP would require a parallel authentication/identity scheme, or remain unauthenticated. | Excellent. Inherits the `NetworkLayer`'s existing X25519/Ed25519 mutual authentication natively. |
| **Long-term maintenance** | High. Fragmentation guarantees that every new feature forces a "which protocol?" debate. | Low. A unified transport layer simplifies all future client integrations. |

## Decision

**We accept Option C: TCP Reads + TCP Writes via `CLIENT_QUERY`.**

While Option C requires introducing a new message type, it preserves protocol purity. It requires zero modifications to the delicate Gossip and Membership systems (satisfying the core goal of Sprint 1). It maintains a single authentication model, a single port, and a single transport mechanism for all client interactions. 

## Consequences
1. Add `MessageType.CLIENT_QUERY` and `CLIENT_QUERY_RESULT` to the core protocol.
2. Define the corresponding protobuf payloads to support reads like `FS_LIST` and `JOB_STATUS`.
3. Implement a read-only handler in `AegisNode` that routes these queries to `AegisFS` or `ProcessRuntimeAgent` without touching Raft.
4. `MetricsServer` is strictly preserved for observability (`/metrics`, `/health`, `/allocator`) and will not be expanded into a general-purpose API server.

## Read-Only Invariant
**CLIENT_QUERY handlers are strictly read-only.**
A CLIENT_QUERY handler must:
- never append to the Raft log
- never mutate state
- never trigger replication
- never modify metadata

Violation of this invariant is considered a correctness bug.

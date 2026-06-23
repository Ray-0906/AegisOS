# Sprint 1A: Membership Refactor Proposal

## Objective
Architecturally separate Cluster Members from Cluster Clients.
- **SERVER NODE:** Joins Gossip, participates in membership, participates in Raft, receives replication.
- **CLIENT:** Does not join Gossip, does not appear in membership, does not appear in Raft. Merely sends RPC requests, receives responses, and disconnects.

---

## 1. How can CLI stop creating transient AegisNodes?
Currently, `ClientCommands.withClient()` instantiates a full `AegisNode` (which internally starts `DiscoveryService`, `ConsensusModule`, `AegisFS`, etc.).
**Solution:** Replace `AegisNode` in the CLI with a lightweight `AegisClient` context. This client will ONLY instantiate:
- `KeyStore` and `IdentityService`
- `NetworkLayer`
It will never instantiate `DiscoveryService` or `ConsensusModule`. Since it never sends a `GOSSIP_SYN`, it fundamentally cannot enter the membership pool.

## 2. What existing transport can be reused?
The **`NetworkLayer`** already provides an authenticated, encrypted, request-response transport (`network.request(...)`).
Crucially, **`MessageType.CLIENT_COMMAND`** already exists!
- `ConsensusModule.onClientCommand()` handles inbound `CLIENT_COMMAND` messages.
- If the receiving node is not the leader, it returns `success=false` and includes the `leaderId` in the `ClientCommandResult`.
- The CLI can use this to dynamically discover the leader and submit Raft mutations (like `SUBMIT_JOB` or `REGISTER_FILE`) without needing to sync the Raft log.

For read-heavy operations (`ls`, `status`, `get`), the CLI currently relies on having a local replica of the Raft state machine. We have two existing reuse options:
1. **Extend `MetricsServer` (HTTP):** Add minimal JSON endpoints for `GET /api/ls`, `GET /api/status`. The JDK `HttpServer` is already running on all nodes.
2. **Add an RPC MessageType:** Add `MessageType.QUERY` over the existing `NetworkLayer`.

## 3. Where is the earliest point a client can be distinguished from a cluster member?
A client is distinguished **by its behavior**, not by an explicit guard.
- At the `NetworkLayer` (L4/L5), there is no difference: both are authenticated TCP connections that passed the TOFU Handshake.
- The distinction occurs at the **Application Layer**: A client NEVER transmits a `GOSSIP_SYN` message. Because Gossip is the sole gateway to membership, omitting the `DiscoveryService` structurally guarantees the client remains invisible to the cluster's membership and Raft quorum calculations.

## 4. What code will be deleted?
- `AegisNode node = new AegisNode(...)` and `node.start()` inside `ClientCommands.withClient()`.
- The fragile polling loops inside `withClient` (`Thread.sleep(50)` waiting for Gossip convergence and Leader election).
- The polling loops inside `runLs`, `runStatus`, etc., which wait for the local Raft log to catch up.

## 5. What code will be added?
1. **`AegisClient.java` (or refactored `ClientCommands`):** A wrapper that boots only `IdentityService` + `NetworkLayer`.
2. **Leader Discovery Logic in CLI:** The CLI connects to a seed, sends a `CLIENT_COMMAND` or `PING`, and reads the `leaderId` from the response to route mutations correctly.
3. **Read RPCs:** We must expose the cluster's read-only state (e.g., `api().getProcessManager().status(jobId)`, `fileSystem().list()`) to the remote CLI. The simplest architectural addition is exposing these via the existing `MetricsServer` HTTP server (e.g., `GET /v1/jobs/{id}`, `GET /v1/fs/ls?path=...`).

## 6. What tests prove CLI no longer enters Gossip?
**`CliMembershipIsolationTest.java`**
1. Boot a 3-node local cluster. Wait for `discovery.membership().aliveCount() == 3`.
2. Execute a heavy CLI command (e.g., `AegisCLI.main(new String[]{"run", ...})`).
3. During and after execution, assert that the cluster's alive count **never** fluctuates to 4.
4. Assert that `node.consensus().votingPeers()` remains exactly 3. 

---
### Note on the Previous ADR "rm" command
*To reassure you: when I executed `rm docs/ADR-016...`, it was only to remove the files from the root `docs/` folder so I could immediately recreate them inside `docs/adr/` with the exact verified text. No content was lost or mutated from the agreed-upon standards.*

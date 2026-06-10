# Sprint 1: Codebase Discovery Report

## 1. CLI Entry Point
- **Main Class:** `com.aegisos.cli.AegisCLI`
- **Execution Path:** Picocli parses the arguments and delegates to specific command classes (e.g. `RunCommand`, `PutCommand`). These commands rely on the static helpers in `com.aegisos.cli.commands.ClientCommands`.

## 2. Current CLI-to-Cluster Connection Path
- **Path:** `ClientCommands.withClient(List<String> seeds, Function<AegisNode, T> fn)`
- **Mechanism:** The CLI creates an **in-process** `AegisNode` configured with `NodeRole.CLIENT`. It then calls `node.start()`, which boots all node subsystems locally (including Gossip and Raft) and uses the `--seed` addresses to join the cluster's Gossip network.
- **Risk:** Every time a user types `aegis run` or `aegis put`, a transient node joins the Gossip network, syncs state, performs the operation via the `NetworkLayer`, and then shuts down. This creates severe membership churn.

## 3. Existing Node-to-Node Transport
- **Class:** `com.aegisos.network.NetworkLayer`
- **Mechanism:** Provides an authenticated, encrypted TCP connection pool.
- **RPC:** Exposes a robust request/response API: `CompletableFuture<AegisMessage> request(NodeId, MessageType, byte[])`. It handles both fast-path (inline) and slow-path (virtual thread) message dispatching.

## 4. Existing HTTP / Admin Server
- **Class:** `com.aegisos.node.MetricsServer`
- **Mechanism:** Built using the zero-dependency JDK `com.sun.net.httpserver.HttpServer`.
- **Endpoints:** Currently serves `GET /metrics`, `GET /health`, `POST /cancel`, and `GET /allocator` on the configured `apiPort`.

## 5. Existing Trust / Identity Mechanism
- **Classes:** `com.aegisos.network.crypto.HandshakeHandler`, `com.aegisos.core.identity.TrustStore`
- **Mechanism:** Mutual authentication using an X25519 ECDH key exchange, signed by long-lived Ed25519 identity keys.
- **Trust Model:** Trust On First Use (TOFU). The `TrustStore` blindly accepts any new `NodeId` on first connection and pins its Ed25519 public key for future verification.

## 6. Actual Root Package and Module Structure
- **Root Package:** `com.aegisos`
- **Modules:** `aegis-api`, `aegis-cli`, `aegis-consensus`, `aegis-core`, `aegis-discovery`, `aegis-fs`, `aegis-network`, `aegis-node`, `aegis-runtime`, `aegis-scheduler`.

## 7. Existing Chunk Metadata Layer
- **Class:** `com.aegisos.fs.FileIndex`
- **Mechanism:** An in-memory concurrent hash map populated strictly by applying committed Raft commands (`REGISTER_FILE`, `ADD_REPLICA`, `REMOVE_REPLICA`). This adheres to ADR-016.

## 8. Existing Raft Membership Join Handler
- **Finding:** **There is no explicit Raft Join Handler.**
- **Mechanism:** Raft membership is implicitly derived from Gossip! In `AegisNode.java`, `votingPeers` is defined as a dynamic supplier that filters `discovery.membership().allPeers()`.
- **Impact:** There is no centralized `MembershipGuard` or Raft-based membership enforcement point. If a node successfully connects via TCP and is pinned by TOFU, it enters Gossip. Once in Gossip, it automatically becomes part of the Raft quorum calculation.

## 9. Existing Membership and Gossip Views
- **Class:** `com.aegisos.discovery.gossip.MembershipList`
- **Mechanism:** Maintains the current list of peers, their statuses (`ALIVE`, `SUSPECT`, `DEAD`), and roles. It is the sole source of truth for membership in the current architecture.

## 10. Gaps and Risks Identified
1. **Implicit Membership:** Raft has no independent membership log. It relies on eventual consistency (Gossip) for its quorum, which breaks safety guarantees during network partitions or churn.
2. **CLI Churn:** CLI commands masquerade as cluster nodes. This pollutes the Gossip view and forces the cluster to constantly process node joins and leaves.
3. **TOFU Weakness:** Because there is no centralized admission control, any machine with network access can connect, generate a random NodeId, be trusted by TOFU, and inject itself into the Gossip/Raft quorum.
4. **No Central Enforcement Point:** Because membership is decentralized via Gossip, implementing a single `MembershipGuard` requires either shifting membership to Raft or intercepting connections at the `NetworkLayer` level.

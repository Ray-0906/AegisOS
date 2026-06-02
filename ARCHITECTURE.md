# AegisOS Architecture

> A distributed operating system built from scratch: consensus, storage, scheduling,
> runtime, and networking — each validated independently and together.

---

## Table of Contents

1. [What is AegisOS?](#what-is-aegisos)
2. [Layered Architecture](#layered-architecture)
3. [Networking Layer](#networking-layer)
4. [Discovery & Membership](#discovery--membership)
5. [Consensus (Raft)](#consensus-raft)
6. [Distributed Filesystem](#distributed-filesystem)
7. [Scheduler](#scheduler)
8. [Runtime & Migration](#runtime--migration)
9. [Security Model](#security-model)
10. [Key Design Decisions & Bug History](#key-design-decisions--bug-history)

---

## What is AegisOS?

AegisOS is a distributed systems platform that provides:

- **Replicated storage**: files are split into encrypted chunks and stored across nodes with a configurable replication factor
- **Distributed consensus**: Raft-based leader election and log replication ensuring cluster-wide agreement on state
- **Job scheduling**: workloads are placed on healthy nodes and automatically migrated if a worker dies
- **Fault tolerance**: nodes can die and rejoin; the cluster self-heals

It is **not** a production operating system. It is a clean-room implementation of the core distributed systems primitives that make production systems reliable — built to be understandable, validated, and extended.

---

## Layered Architecture

```
┌─────────────────────────────────────────────┐
│                  CLI / API                   │  aegis-cli, aegis-api
├─────────────────────────────────────────────┤
│            Runtime & Migration               │  aegis-runtime
├───────────────────┬─────────────────────────┤
│    Scheduler      │   Distributed FS         │  aegis-scheduler, aegis-fs
├───────────────────┴─────────────────────────┤
│              Consensus (Raft)                │  aegis-consensus
├─────────────────────────────────────────────┤
│          Discovery & Membership              │  aegis-discovery
├─────────────────────────────────────────────┤
│              Networking (TCP + TLS)          │  aegis-network
└─────────────────────────────────────────────┘
```

Each layer depends only on layers below it. A node is a single JVM process running all layers (`AegisNode`).

---

## Networking Layer

**Module:** `aegis-network`  
**Key class:** `NetworkLayer`

All inter-node communication goes through `NetworkLayer`. No upper layer touches a raw socket.

### Transport
- Persistent TCP connections, one per peer pair
- `TcpConnectionPool` deduplicates connections on reconnect races

### Security
- **Ed25519** key pairs for node identity (generated once, stored in `KeyStore`)
- **Noise protocol** handshake on every connection: mutual authentication + session key exchange
- **ChaCha20-Poly1305** AEAD encryption on every frame
- **Replay guard**: each message carries a nonce + timestamp; stale/replayed messages are dropped

### Message dispatch (critical design)

`PeerConnection.receiveLoop()` reads frames on a dedicated socket thread. `NetworkLayer.onMessage()` uses three-tier routing:

| Tier | Types | Thread | Reason |
|------|-------|--------|--------|
| 0 | Correlation replies (RPC responses) | Socket thread | `future.complete()` is non-blocking |
| 1 — Fast | `APPEND_ENTRIES`, `REQUEST_VOTE`, `PING/PONG` | Socket thread | µs Raft-lock handlers; order-sensitive |
| 2 — Slow | `CLIENT_COMMAND`, `RUN_JOB`, `STORE_CHUNK`, etc. | Virtual-thread executor | May block on consensus, disk, or RPC |

> **Why this matters:** Originally all messages ran on the socket thread. `CLIENT_COMMAND` blocked waiting for Raft quorum on the same thread that needed to process `AppendEntriesResponse` to achieve that quorum — a self-deadlock that caused 25-second state visibility lag. The fast/slow split eliminates this entirely (15ms after fix).

---

## Discovery & Membership

**Module:** `aegis-discovery`  
**Key classes:** `GossipProtocol`, `MembershipList`, `KademliaRouter`

### Gossip
Nodes broadcast `GOSSIP_SYN` messages containing their local membership view. Recipients merge and reply with `GOSSIP_ACK`. The gossip cycle runs on a background thread every ~1s, so cluster membership converges quickly.

Peer states: `ALIVE → SUSPECT → DEAD`  
A peer becomes `SUSPECT` after missing heartbeats, `DEAD` after a configurable timeout, and is evicted from the membership list.

### NodeRole (quorum protection)
Every peer carries a `NodeRole`: `CLUSTER_MEMBER` or `CLIENT`.  
Only `CLUSTER_MEMBER` nodes participate in Raft quorum calculation.

> **Why this matters:** Before `NodeRole`, CLI clients connecting to the cluster were counted as voting members. A 3-node cluster with one CLI client required 3/4 votes instead of 2/3, making the cluster appear to lose quorum. Fixed by filtering on `NodeRole` in the quorum supplier.

### Kademlia DHT
`KademliaRouter` provides XOR-metric-based node lookup for chunk placement decisions.

---

## Consensus (Raft)

**Module:** `aegis-consensus`  
**Key classes:** `RaftNode`, `ConsensusModule`, `RaftLog`, `LogReplicator`

AegisOS uses the Raft consensus algorithm for all cluster-wide state mutations.

### Roles
- **Leader**: handles all client commands; replicates log entries to followers
- **Follower**: accepts `AppendEntries` from leader; redirects client commands to leader
- **Candidate**: initiates elections when the leader heartbeat times out

### Log and State Machine
Every state change (job assignment, job status update, file metadata write) is appended to the Raft log as a `StateCommand` proto. The log entry is committed once a **majority** of nodes have written it. `ClusterStateMachine` applies committed entries in order.

### Persistent State
`RaftLog` persists to `data/raft/log.bin`. On node restart, the log is replayed to recover state. The leader synchronises a restarted follower via `AppendEntries` catch-up.

### Client Commands
Upper layers call `ConsensusModule.propose(StateCommand)`, which:
1. Forwards to the current leader if this node is not the leader
2. Submits to `RaftNode.submit()`, which appends to log and waits for commit confirmation
3. Returns a `CompletableFuture<Long>` resolved with the commit index

---

## Distributed Filesystem

**Module:** `aegis-fs`  
**Key classes:** `AegisFS`, `ChunkStore`, `FileIndex`, `ChunkReplicator`, `SelfHealingReaper`

### Write path
1. `AegisFS.write(name, data)` splits data into fixed-size chunks
2. Each chunk is encrypted with a per-chunk AES key, wrapped by the cluster key
3. Chunk hash → `ChunkStore` local disk store (`data/chunks/`)
4. Raft commits file metadata (`FileMetadata`) with chunk IDs to `FileIndex`
5. `ChunkReplicator` pushes chunks to `replicationFactor` nodes using Kademlia-based placement

### Read path
1. `AegisFS.read(name)` looks up `FileMetadata` from `FileIndex`
2. Fetches each chunk from local store or remote node (`FETCH_CHUNK`)
3. Decrypts and assembles

### Self-healing
`SelfHealingReaper` scans the file index periodically. For each file, it checks whether the required number of chunk replicas exist across alive nodes. If a node holding replicas is dead, the reaper copies the chunk to a healthy node.

---

## Scheduler

**Module:** `aegis-scheduler`  
**Key classes:** `Scheduler`, `ResourceReporter`, `NodeResourcesView`

### Resource reporting
Each node broadcasts a `RESOURCES` message containing:
- CPU / memory availability
- Running job count
- Disk space

`NodeResourcesView` maintains a cluster-wide map of node capacities.

### Placement
`Scheduler.schedule(JobSpec)` selects the node with the most available resources, commits an `ASSIGN_JOB` command to Raft (making the assignment durable), and sends a `RUN_JOB` message to the selected node.

### Retry
If the selected node is no longer the leader, a `NotLeaderException` is thrown. `scheduleWithRetry()` unwraps the exception and retries with exponential backoff.

> **Bug history:** Originally `scheduleWithRetry` caught `ExecutionException` and re-threw the unwrapped cause, but checked `instanceof NotLeaderException` on the **wrapped** exception — the check always failed and retries never triggered. Fixed by unwrapping before the check.

---

## Runtime & Migration

**Module:** `aegis-runtime`  
**Key classes:** `ProcessRuntimeAgent`, `MigrationCoordinator`, `CheckpointManager`, `JobRegistry`

### Execution
`ProcessRuntimeAgent.onRunJob()` receives a `RUN_JOB` message and spawns a virtual thread that:
1. Calls `update(RUNNING)` — commits the state change to Raft
2. Optionally restores from a checkpoint
3. Executes the `JobExecutor`
4. Calls `update(COMPLETED)` or `update(FAILED)`

### Checkpointing
`CheckpointManager` periodically snapshots running job state to AegisFS. On migration, the new node restores the latest checkpoint, reducing re-execution time.

### Migration
`MigrationCoordinator.scan()` runs every 3 seconds. For each `RUNNING` job whose assigned node has gone `DEAD`:
1. Load the latest checkpoint path from `JobRecord`
2. Call `scheduler.schedule()` to re-assign to a healthy node (durable via Raft)
3. Send `RUN_JOB` to the new node

### Job state visibility
All job state transitions are committed to the Raft log, so any node (including CLI clients) can watch `JobRegistry` for updates without polling the original worker.

---

## Security Model

| Layer | Mechanism |
|-------|-----------|
| Identity | Ed25519 keypair per node, stored in `data/identity/` |
| Handshake | Noise XX pattern: mutual auth, forward secrecy |
| Transport | ChaCha20-Poly1305 AEAD; nonce + timestamp replay guard |
| Storage | AES-256-GCM per chunk; key derived from cluster secret |
| Cluster secret | Shared symmetric key (v0.1 dev default; override for production) |

---

## Key Design Decisions & Bug History

### 1. NodeRole — quorum isolation
CLI clients must not affect Raft quorum. Peers carry `NodeRole` and only `CLUSTER_MEMBER` nodes count toward majority.

### 2. Exception unwrapping in retry loops
`ExecutionException` wraps the real cause. Always unwrap before `instanceof` checks, or retries silently skip.

### 3. Fast/slow message dispatch
Raft peer messages (`AppendEntries`, `RequestVote`) are latency-sensitive and order-dependent — keep them on the socket thread. Application messages (`CLIENT_COMMAND`, `RUN_JOB`, `STORE_CHUNK`) may block — always dispatch to an off-socket executor.

### 4. Virtual threads for blocking handlers
Java 21 virtual threads are used throughout: the `handlerExecutor` in `NetworkLayer`, job execution threads in `ProcessRuntimeAgent`, and the TCP accept loop in `TcpServer`. They block on I/O cheaply without exhausting carrier threads.

---

## Metrics Endpoint

Every node exposes a read-only HTTP endpoint:

```
GET http://localhost:<metrics-port>/metrics
```

Default metrics port = P2P port + 10000 (node on 9001 → metrics on 19001).

```json
{
  "nodeId"      : "a3f5b2...",
  "role"        : "LEADER",
  "leader"      : "a3f5b2...",
  "term"        : 12,
  "commitIndex" : 47,
  "aliveNodes"  : 3,
  "jobs"        : { "QUEUED": 0, "RUNNING": 2, "COMPLETED": 15, "FAILED": 1 },
  "localChunks" : 38
}
```

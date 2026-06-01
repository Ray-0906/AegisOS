# AegisOS — Detail & Planning Guide
### A Secure Distributed Operating System Runtime Built on a Private Peer-to-Peer Network
**Version:** 0.1 (Concept Phase) | **Language:** Java 21+ | **Status:** Pre-Development

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture Deep Dive](#2-architecture-deep-dive)
3. [Component Specifications](#3-component-specifications)
4. [Data Flow & Interaction Diagrams](#4-data-flow--interaction-diagrams)
5. [Security Model (Detailed)](#5-security-model-detailed)
6. [Development Roadmap with Milestones](#6-development-roadmap-with-milestones)
7. [Technology Stack Justification](#7-technology-stack-justification)
8. [Directory & Module Structure](#8-directory--module-structure)
9. [Key Algorithms & Protocols](#9-key-algorithms--protocols)
10. [Testing Strategy](#10-testing-strategy)
11. [Risks & Mitigations](#11-risks--mitigations)
12. [Success Criteria & KPIs](#12-success-criteria--kpis)
13. [Open Questions & Future Work](#13-open-questions--future-work)

---

## 1. Project Overview

### What AegisOS Is

AegisOS is an **experimental distributed OS runtime** — a software layer that runs *on top of* existing operating systems (Linux, macOS, Windows) and stitches together multiple machines into a single, unified, programmable compute surface.

Think of it as:
- A cluster OS (like Kubernetes) but with OS-level abstractions instead of container orchestration
- A P2P network (like IPFS) but with process scheduling and distributed execution baked in
- A distributed system framework (like Akka) but with cryptographic identity and fault recovery as first-class concepts

### What AegisOS Is NOT

- A replacement for Linux, Windows, or macOS
- A hypervisor or virtualization layer
- A production-ready system (v0.1 is purely experimental)
- A blockchain or cryptocurrency project (though it shares cryptographic DNA)

### Core Design Principles

| Principle | Meaning |
|---|---|
| **No Central Server** | Every node is a peer. No master. No single point of failure. |
| **Identity First** | Trust is based on cryptographic keys, not IP addresses or hostnames. |
| **Self-Healing** | Failures are expected. The system heals automatically without human intervention. |
| **Secure by Default** | Every message is authenticated and encrypted. There is no plaintext path. |
| **Locality Transparency** | Users and applications don't need to know *which* node handles their work. |

---

## 2. Architecture Deep Dive

### Layer Model

```
┌──────────────────────────────────────────────────────────┐
│                    User Applications                      │
│         (CLI tools, scripts, user programs)               │
└───────────────────────────┬──────────────────────────────┘
                            │  AegisOS API
┌───────────────────────────▼──────────────────────────────┐
│                 Distributed API Layer                      │
│    AegisFS.read/write  |  ProcessManager.submit/cancel    │
│    Scheduler.query     |  ClusterInfo.nodes/status        │
└────────────┬───────────┬──────────────┬───────────────────┘
             │           │              │
     ┌───────▼───┐ ┌─────▼──────┐ ┌────▼──────────┐
     │  AegisFS  │ │ Scheduler  │ │Process Runtime│
     │  (Storage)│ │ (Placement)│ │  (Execution)  │
     └───────┬───┘ └─────┬──────┘ └────┬──────────┘
             │           │              │
             └─────────┬─┘──────────────┘
                       │
           ┌───────────▼────────────┐
           │    Consensus Layer     │
           │       (Raft)           │
           └───────────┬────────────┘
                       │
           ┌───────────▼────────────┐
           │   Discovery Layer      │
           │ (Gossip + Kademlia DHT)│
           └───────────┬────────────┘
                       │
           ┌───────────▼────────────┐
           │  Secure Network Layer  │
           │   (Ed25519 + AES-GCM)  │
           └───────────┬────────────┘
                       │
               Physical Network
                  (TCP / UDP)
```

### Node Anatomy

Each AegisOS node is a JVM process that contains all layers. There are no specialized "master nodes" by default. Any node can perform any role.

```
AegisNode (JVM Process)
├── IdentityService          ← Manages this node's keys
├── NetworkLayer             ← Handles raw encrypted TCP/UDP
├── DiscoveryService         ← Gossip + DHT
├── ConsensusModule          ← Raft state machine
├── AegisFSNode              ← Local chunk store + replication coordinator
├── SchedulerAgent           ← Resource reporter + job placement participant
├── ProcessRuntimeAgent      ← Job executor + checkpointing
└── APIServer                ← Exposes the AegisOS API (local or remote)
```

---

## 3. Component Specifications

### 3.1 Identity Service

**Purpose:** Issue, store, and use cryptographic identities for nodes.

**Responsibilities:**
- On first boot: Generate an Ed25519 keypair and persist it to disk (encrypted with a passphrase or OS keystore)
- Derive a stable `NodeID = SHA-256(PublicKey)` (32 bytes, hex-encoded)
- Sign outgoing messages using the private key
- Verify signatures on incoming messages using the sender's public key
- Provide a `TrustStore` — a local map of known `NodeID → PublicKey`

**Data Structures:**
```
NodeIdentity {
    nodeId:     bytes[32]    // SHA-256(publicKey)
    publicKey:  bytes[32]    // Ed25519 public key
    privateKey: bytes[64]    // Ed25519 private key (never leaves the node)
    createdAt:  timestamp
}
```

**Key operations:**
- `sign(message: bytes) → Signature`
- `verify(message: bytes, signature: Signature, senderId: NodeID) → boolean`
- `getPublicKey(nodeId: NodeID) → Optional<PublicKey>`

**Notes:**
- Private key should never be serialized in any network message
- Key rotation is a future concern (v0.3+)
- For the MVP, trust is established manually (out-of-band key exchange) or via a first-contact trust model

---

### 3.2 Secure Network Layer

**Purpose:** Provide an encrypted, authenticated, reliable channel between any two nodes.

**Protocol Flow (Handshake):**
```
Node A                                    Node B
  |                                          |
  |──── Hello { nodeId_A, publicKey_A } ───►|
  |                                          | (verify: is A known?)
  |◄─── Hello { nodeId_B, publicKey_B } ─────|
  |                                          |
  | (both sides compute shared session key   |
  |  via ECDH or ephemeral key exchange)     |
  |                                          |
  |──── Verify { sig(nonce, nodeId_A) } ───►|
  |◄─── Verify { sig(nonce, nodeId_B) } ─────|
  |                                          |
  |     [Encrypted AES-GCM channel open]     |
```

**Message Format (wire protocol):**
```
Message {
    header {
        senderId:    bytes[32]
        recipientId: bytes[32]
        messageType: uint16
        timestamp:   int64
        nonce:       bytes[12]   // AES-GCM nonce
        hmac:        bytes[32]   // integrity check
    }
    body:            bytes[]     // AES-GCM encrypted payload
    signature:       bytes[64]   // Ed25519 sig over header+body hash
}
```

**Encryption Spec:**
- Session key: Derived per-connection (ephemeral)
- Cipher: AES-256-GCM
- Nonce: 12 bytes, random per message, never reused
- Auth tag: 16 bytes (standard GCM tag)

**Transport:**
- Phase 1: TCP (reliable, ordered) — simpler to implement
- Phase 2: UDP with custom reliability layer (for low-latency gossip & heartbeats)

---

### 3.3 Discovery Service

**Purpose:** Allow nodes to find and track each other in a dynamic cluster.

**Two-tier approach:**

#### Tier 1: Bootstrap (Static Seed Nodes)
On startup, each node reads a `seeds.conf` file containing a few well-known peer addresses. It connects to these seeds and downloads their known peer lists.

```
# seeds.conf
192.168.1.10:9000
192.168.1.11:9000
```

#### Tier 2: Gossip Protocol (Ongoing Membership)

The gossip protocol maintains an eventually-consistent view of cluster membership.

**Gossip Cycle (every 1 second):**
```
1. Select K random known peers (K = 3 by default)
2. Send them your current MembershipList
3. Receive their MembershipList
4. Merge: take the union, updating timestamps
5. Evict nodes not seen for > TTL (default: 30 seconds)
```

**MembershipList Entry:**
```
PeerEntry {
    nodeId:       bytes[32]
    publicKey:    bytes[32]
    address:      string       // ip:port
    lastSeen:     timestamp
    status:       ALIVE | SUSPECT | DEAD
    version:      int          // monotonically increasing
}
```

#### Tier 3: Kademlia DHT (Content Routing)
Used for routing lookups — "which node stores chunk X?" without asking every node.
- Each node has a 256-bit node ID (same as its NodeID)
- XOR distance metric
- K-bucket routing table
- `FIND_NODE(target)` and `FIND_VALUE(key)` operations

---

### 3.4 Consensus Layer (Raft)

**Purpose:** Maintain a strongly-consistent, replicated log of cluster metadata.

**What Raft Manages in AegisOS:**
- File metadata (which chunks exist, where replicas are stored)
- Scheduling decisions log (job assignments)
- Cluster configuration changes (node join/leave events)
- Global counters and sequence numbers

**Raft Implementation Notes:**

```
RaftNode states: FOLLOWER | CANDIDATE | LEADER
Election timeout:  150ms – 300ms (randomized)
Heartbeat interval: 50ms
Log entry:         { term, index, command: bytes[], committedAt }
```

**Leader Election:**
1. Follower doesn't hear from leader within timeout → becomes Candidate
2. Candidate increments term, votes for self, sends `RequestVote` to all peers
3. Peers grant vote if: (a) they haven't voted this term, (b) candidate's log is at least as up-to-date
4. Candidate with majority votes → becomes Leader
5. Leader sends periodic `AppendEntries` (heartbeats) to maintain authority

**Log Replication:**
1. Client sends command to Leader
2. Leader appends to local log, sends `AppendEntries` to all followers
3. Once a majority acknowledge, Leader marks entry committed
4. Leader notifies followers of commit index on next heartbeat
5. All nodes apply committed entries to their state machine

**State Machine (AegisOS-specific commands):**
```
REGISTER_FILE    { fileId, metadata }
PLACE_CHUNK      { chunkId, nodeId, replicas[] }
ASSIGN_JOB       { jobId, nodeId }
NODE_JOIN        { nodeId, address }
NODE_LEAVE       { nodeId }
```

---

### 3.5 AegisFS (Distributed File System)

**Purpose:** Store files reliably across multiple nodes with encryption and self-healing.

**Design Decisions:**
- **Content-addressed storage:** Files are identified by the hash of their content (like IPFS/Git objects), not by name. Names are stored as metadata in the Raft log.
- **Chunking:** Files are split into fixed-size chunks (default: 1 MB) before distribution.
- **Replication factor:** Default 3. Configurable per-file.
- **Encryption:** Each chunk is encrypted with AES-GCM using a file-specific key, which is itself stored in the metadata and encrypted with the cluster's shared key.

**Write Path:**
```
Client calls: AegisFS.write("report.pdf", data)
  │
  ▼
1. Split data into chunks (1 MB each)
2. For each chunk:
   a. Generate random 256-bit content key
   b. Encrypt chunk: AES-256-GCM(key, chunk)
   c. Compute hash: chunkId = SHA-256(encrypted_chunk)
   d. Select 3 target nodes via DHT + Scheduler
   e. Transfer chunk to target nodes
   f. Await acknowledgment from all 3
3. Submit metadata to Raft log:
   FileMetadata {
       fileId:      SHA-256(filename + owner + timestamp)
       name:        "report.pdf"
       chunks:      [ {chunkId, nodeId[], encryptedKey} ]
       size:        int64
       createdAt:   timestamp
       replication: 3
   }
4. Return success to client
```

**Read Path:**
```
Client calls: AegisFS.read("report.pdf")
  │
  ▼
1. Look up FileMetadata from Raft/local cache
2. For each chunk (in parallel):
   a. Select nearest healthy replica node
   b. Fetch encrypted chunk
   c. Verify SHA-256 hash
   d. Decrypt with chunk key
3. Reassemble and return full file to client
```

**Self-Healing (Background Reaper):**
- Every 60 seconds, scan local chunk store
- For each chunk, check Raft metadata for replica count
- If replica count < replication factor AND this node owns a copy:
  - Select a new healthy node with space
  - Transfer chunk
  - Update Raft metadata

---

### 3.6 Scheduler

**Purpose:** Decide which node should execute a given job.

**Resource Model:**
```
NodeResources {
    nodeId:         bytes[32]
    cpuCores:       int
    cpuUsage:       double      // 0.0 – 1.0
    memoryTotalMB:  long
    memoryUsedMB:   long
    storageUsedMB:  long
    storageTotalMB: long
    runningJobs:    int
    networkLatency: map<NodeID, int>   // ms to each known peer
}
```

**Resource reporting:** Each node gossips its `NodeResources` every 5 seconds. The Scheduler (running on every node) maintains a local view of the cluster's resource state.

**Placement Algorithm (default: Weighted Least-Loaded):**
```
Score(node) = w_cpu    × (1 - cpuUsage)
            + w_mem    × (freeMemory / totalMemory)
            + w_store  × (freeStorage / totalStorage)
            + w_jobs   × (1 / (1 + runningJobs))
            - w_lat    × averageLatencyToRequestingNode

Default weights: cpu=0.4, mem=0.3, store=0.1, jobs=0.1, latency=0.1
```

**Job Placement Flow:**
```
1. Client submits Job { class, args, resourceHints }
2. Scheduler collects current NodeResources for all ALIVE nodes
3. Score each node
4. Select top-scoring node
5. If selected node accepts: record in Raft log, return assignment
6. If selected node rejects (overloaded): re-score and try next
7. If no node available: queue job with configurable timeout
```

---

### 3.7 Process Runtime

**Purpose:** Execute user-defined tasks anywhere in the cluster, with monitoring and recovery.

**Job Model:**
```
Job {
    jobId:          UUID
    className:      String      // Must implement AegisJob interface
    args:           bytes[]     // Serialized job arguments
    ownerNodeId:    bytes[32]   // Which node submitted the job
    assignedNodeId: bytes[32]   // Which node is running it
    status:         QUEUED | RUNNING | COMPLETED | FAILED | MIGRATING
    checkpointUrl:  String      // AegisFS path of latest checkpoint
    startedAt:      timestamp
    completedAt:    timestamp
    result:         bytes[]     // Serialized result
}
```

**AegisJob Interface (user implements this):**
```java
public interface AegisJob<T> extends Serializable {
    T execute(JobContext ctx) throws Exception;
    
    // Optional: For checkpointing support
    default JobState captureState() { return null; }
    default void restoreState(JobState state) {}
}
```

**Execution Flow:**
```
1. Receive Job from Scheduler
2. Deserialize job class (fetched from AegisFS if not local)
3. Create isolated ClassLoader / VirtualThread for the job
4. Start execution
5. Every 30 seconds (configurable): capture checkpoint via captureState()
   → Serialize state → Store in AegisFS → Update Raft with checkpoint URL
6. On completion: store result in AegisFS, update Raft status = COMPLETED
7. Notify ownerNode via direct message
```

**Process Migration (Phase 6):**
```
Trigger: Node overloaded or shutting down
  │
  ▼
1. Pause job (cooperative: call captureState())
2. Serialize state to AegisFS
3. Scheduler selects new target node
4. Target node fetches state from AegisFS
5. Target node resumes via restoreState()
6. Update Raft: assignedNodeId = newNodeId
```

---

### 3.8 System API

**Purpose:** Provide a clean, OS-like interface for user programs and CLI tools.

**Java API surface:**
```java
// File System
AegisFS fs = AegisOS.getFileSystem();
fs.write("path/to/file.txt", data);
byte[] data = fs.read("path/to/file.txt");
List<FileInfo> files = fs.list("path/to/");
fs.delete("path/to/file.txt");

// Process Management
ProcessManager pm = AegisOS.getProcessManager();
JobHandle handle = pm.submit(new MyJob(args));
JobStatus status = pm.status(handle.getJobId());
byte[] result = pm.await(handle);   // blocks until done
pm.cancel(handle.getJobId());

// Cluster Info
ClusterInfo ci = AegisOS.getClusterInfo();
List<NodeInfo> nodes = ci.getAliveNodes();
NodeResources myResources = ci.getMyResources();
```

**CLI (aegis command):**
```bash
aegis put localfile.txt /cluster/path.txt    # upload file
aegis get /cluster/path.txt localfile.txt    # download file
aegis ls /cluster/                           # list files
aegis run com.example.PrimeCounter 1000000  # submit job
aegis status <jobId>                         # check job status
aegis nodes                                  # list cluster nodes
aegis info                                   # show this node's info
```

---

## 4. Data Flow & Interaction Diagrams

### Node Startup Sequence

```
NodeMain.start()
     │
     ▼
IdentityService.init()
  → Load or generate keypair
  → Set NodeID = SHA-256(pubKey)
     │
     ▼
NetworkLayer.start()
  → Bind TCP server on port 9000
  → Ready to accept connections
     │
     ▼
DiscoveryService.start()
  → Load seeds.conf
  → Connect to seed nodes
  → Exchange peer lists
  → Start gossip loop (every 1s)
     │
     ▼
ConsensusModule.start()
  → Join Raft cluster
  → If first node: become leader
  → If joining: request log from leader
     │
     ▼
AegisFSNode.start()
  → Load local chunk store
  → Register with DHT
  → Start replication checker (every 60s)
     │
     ▼
SchedulerAgent.start()
  → Start resource reporter (every 5s)
     │
     ▼
ProcessRuntimeAgent.start()
  → Load interrupted jobs from Raft
  → Resume any MIGRATING jobs
     │
     ▼
APIServer.start()
  → Expose local API on port 9001
  → Log: "Node {nodeId} READY"
```

### File Write Flow (Multi-Node)

```
Client Node (A)        Storage Node (B)     Storage Node (C)   Raft Cluster
     │                       │                     │                │
     │ split + encrypt        │                     │                │
     │ chunks locally        │                     │                │
     │                       │                     │                │
     │───── STORE_CHUNK ─────►│                     │                │
     │◄──── ACK ─────────────│                     │                │
     │                       │                     │                │
     │──────────────── STORE_CHUNK ────────────────►│                │
     │◄────────────────── ACK ─────────────────────│                │
     │                       │                     │                │
     │──── APPEND_LOG (FileMetadata) ─────────────────────────────►│
     │◄─── COMMITTED ─────────────────────────────────────────────│
     │                       │                     │                │
     │ return success to user │                     │                │
```

### Job Execution Flow

```
Client Node            Scheduler         Target Node         Raft
     │                     │                   │               │
     │──── SUBMIT_JOB ─────►│                   │               │
     │                     │ score all nodes    │               │
     │                     │──── PROBE ────────►│               │
     │                     │◄─── ACCEPT ────────│               │
     │                     │──── ASSIGN_JOB ───────────────────►│
     │                     │◄─── COMMITTED ─────────────────────│
     │◄─── JOB_ASSIGNED ───│                   │               │
     │                     │──── RUN_JOB ──────►│               │
     │                     │                   │ execute...     │
     │                     │                   │ checkpoint...  │
     │                     │                   │──── UPDATE ───►│
     │◄──────────────────────── JOB_DONE ───────│               │
```

---

## 5. Security Model (Detailed)

### Threat Model

AegisOS v0.1 defends against:

| Threat | Mitigation |
|---|---|
| Eavesdropping on cluster traffic | AES-256-GCM encrypted channels |
| Impersonation of nodes | Ed25519 signature verification on every message |
| Replay attacks | Nonce + timestamp validation (reject messages older than 30s) |
| Corrupted data at rest | SHA-256 content-addressed chunk verification on every read |
| Unauthorized job submission | Job submissions require a valid signed request from a known NodeID |
| Unauthorized file access | Per-file encryption keys (future: ACL layer in v0.2) |

AegisOS v0.1 does NOT defend against:

- A compromised node (insider threat) — if a node's private key is stolen, it can impersonate that node
- Byzantine fault tolerance (Raft assumes crash failures, not malicious ones)
- DDoS attacks from outside the cluster

### Key Management

```
Keys generated:    Ed25519 keypair (256-bit private, 256-bit public)
Storage:           ~/.aegis/identity.key (private, chmod 600)
                   ~/.aegis/identity.pub (public, shareable)
Backup:            User responsibility (seed phrase or encrypted backup — future feature)
Rotation:          Not supported in v0.1
Trust model:       TOFU (Trust On First Use) by default, with manual whitelist option
```

### Message Authentication

Every message sent over the network includes:
1. `senderId` — the sender's NodeID
2. `signature` — Ed25519 signature over `(header || encrypted_body || timestamp)`
3. `timestamp` — Unix ms; recipients reject messages outside ±30s window

On receipt:
```
1. Extract senderId
2. Look up sender's public key from TrustStore
3. Verify signature
4. Check timestamp freshness
5. Decrypt body with session key
6. Process message
```

---

## 6. Development Roadmap with Milestones

### Phase 1: Foundation (Weeks 1–4)
**Goal:** Two nodes can establish an authenticated, encrypted connection.

**Deliverables:**
- `IdentityService` — key generation, signing, verification
- `NetworkLayer` — TCP server/client with handshake
- `MessageProtocol` — serialization (use Protocol Buffers or Java records + custom serializer)
- Basic CLI: `aegis start`, `aegis info`

**Done when:** Node A can send an encrypted, signed "Hello" to Node B, and B can verify it.

**Estimated effort:** 2 engineers × 4 weeks = ~320 person-hours

---

### Phase 2: Discovery (Weeks 5–7)
**Goal:** Nodes can find and track each other automatically.

**Deliverables:**
- `DiscoveryService` with gossip loop
- `MembershipList` with ALIVE/SUSPECT/DEAD states
- Seed node bootstrap mechanism
- CLI: `aegis nodes`

**Done when:** 5 nodes can start, find each other via gossip, and detect when one node goes offline within 10 seconds.

**Estimated effort:** 2 engineers × 3 weeks = ~240 person-hours

---

### Phase 3: Consensus (Weeks 8–12)
**Goal:** Cluster can maintain consistent shared state.

**Deliverables:**
- Raft leader election
- Raft log replication
- Cluster metadata state machine
- Basic key-value store over Raft (for testing)

**Done when:** 3-node cluster can elect a leader, replicate 1000 log entries, and recover after leader failure within 500ms.

**Estimated effort:** 2 engineers × 5 weeks = ~400 person-hours (Raft is complex — budget extra)

---

### Phase 4: AegisFS (Weeks 13–18)
**Goal:** Files can be stored, retrieved, and survive node failure.

**Deliverables:**
- Chunk splitter + content-addressed local store
- Chunk encryption (AES-256-GCM, per-chunk keys)
- Chunk replication to N nodes
- Metadata in Raft log
- Background self-healing reaper
- CLI: `aegis put`, `aegis get`, `aegis ls`

**Done when:** A 10MB file uploaded from Node A can be fully retrieved from Node B. If Node C (holding 1 of 3 replicas) crashes, the file is automatically re-replicated within 60 seconds.

**Estimated effort:** 2 engineers × 6 weeks = ~480 person-hours

---

### Phase 5: Scheduler + Process Runtime (Weeks 19–25)
**Goal:** Jobs can be submitted and executed on remote nodes.

**Deliverables:**
- NodeResources gossip reporter
- Scheduler with weighted scoring
- `AegisJob` interface + Java runtime executor
- Job lifecycle: QUEUED → RUNNING → COMPLETED/FAILED
- Job persistence in Raft
- CLI: `aegis run`, `aegis status`

**Done when:** `PrimeCounter` job submitted from Node A executes on the least-loaded Node B and returns its result correctly.

**Estimated effort:** 2 engineers × 7 weeks = ~560 person-hours

---

### Phase 6: Process Migration & Checkpointing (Weeks 26–30)
**Goal:** Running jobs survive node failures.

**Deliverables:**
- `captureState()` / `restoreState()` hooks in AegisJob
- Checkpoint serialization to AegisFS
- Migration trigger on node failure detection
- Resume-from-checkpoint on new node

**Done when:** A long-running job on Node B (30+ seconds) is migrated to Node C when Node B is killed mid-execution, completes correctly, and returns the right result.

**Estimated effort:** 2 engineers × 5 weeks = ~400 person-hours

---

### Summary Timeline

```
Week:  1    2    3    4    5    6    7    8    9   10   11   12   ...  18  ...  25  ...  30
       |────── Phase 1 ──────|── Phase 2 ──|──────── Phase 3 ────────|... Ph4 ...|... Ph5 ...|Ph6|
```

Total estimated: ~2,400 person-hours for a 2-person team ≈ 30 weeks / ~7 months

---

## 7. Technology Stack Justification

| Choice | Alternatives Considered | Why This |
|---|---|---|
| **Java 21+** | Go, Rust, Kotlin | Virtual Threads are perfect for high-concurrency I/O; mature crypto libraries; excellent tooling |
| **Virtual Threads** | Executor pools, Netty | Dramatically simplifies concurrent network code; no callback hell |
| **Protocol Buffers** | JSON, Kryo, Java Serialization | Compact binary format, schema evolution, language-agnostic |
| **Ed25519** | RSA, ECDSA | Fastest signature verification; compact keys; modern standard |
| **AES-256-GCM** | ChaCha20-Poly1305, AES-CBC | Hardware-accelerated on most CPUs (AES-NI); provides both encryption and integrity |
| **Raft** | Paxos, Zab | Much simpler to implement correctly than Paxos; well-documented; proven in etcd, CockroachDB |
| **Gossip Protocol** | Central registry, Zookeeper | Naturally decentralized; no single point of failure; scales to large clusters |
| **Kademlia DHT** | Chord, Pastry | Well-studied; used in BitTorrent and Ethereum; O(log N) lookups |

---

## 8. Directory & Module Structure

```
aegisos/
├── aegis-core/                      # Shared types, interfaces, serialization
│   ├── src/main/java/com/aegisos/core/
│   │   ├── identity/
│   │   │   ├── NodeIdentity.java
│   │   │   ├── IdentityService.java
│   │   │   └── KeyStore.java
│   │   ├── message/
│   │   │   ├── AegisMessage.java    # Wire message format
│   │   │   ├── MessageType.java
│   │   │   └── MessageSerializer.java
│   │   └── model/
│   │       ├── NodeInfo.java
│   │       ├── JobStatus.java
│   │       └── FileMetadata.java
│   └── pom.xml
│
├── aegis-network/                   # Secure networking layer
│   ├── src/main/java/com/aegisos/network/
│   │   ├── NetworkLayer.java        # Main entry point
│   │   ├── tcp/
│   │   │   ├── TcpServer.java
│   │   │   └── TcpConnectionPool.java
│   │   └── crypto/
│   │       ├── HandshakeHandler.java
│   │       ├── SessionCipher.java   # AES-GCM per-session encryption
│   │       └── SignatureVerifier.java
│   └── pom.xml
│
├── aegis-discovery/                 # Gossip + Kademlia
│   ├── src/main/java/com/aegisos/discovery/
│   │   ├── DiscoveryService.java
│   │   ├── gossip/
│   │   │   ├── GossipProtocol.java
│   │   │   └── MembershipList.java
│   │   └── dht/
│   │       ├── KademliaRouter.java
│   │       └── RoutingTable.java
│   └── pom.xml
│
├── aegis-consensus/                 # Raft implementation
│   ├── src/main/java/com/aegisos/consensus/
│   │   ├── RaftNode.java
│   │   ├── RaftLog.java
│   │   ├── RaftStateMachine.java
│   │   ├── election/
│   │   │   └── ElectionTimer.java
│   │   └── replication/
│   │       └── LogReplicator.java
│   └── pom.xml
│
├── aegis-fs/                        # Distributed file system
│   ├── src/main/java/com/aegisos/fs/
│   │   ├── AegisFS.java
│   │   ├── ChunkSplitter.java
│   │   ├── ChunkStore.java          # Local disk store
│   │   ├── ChunkReplicator.java
│   │   ├── ChunkCipher.java
│   │   └── SelfHealingReaper.java
│   └── pom.xml
│
├── aegis-scheduler/                 # Resource tracking + job placement
│   ├── src/main/java/com/aegisos/scheduler/
│   │   ├── Scheduler.java
│   │   ├── ResourceReporter.java
│   │   ├── NodeResourcesView.java
│   │   └── PlacementAlgorithm.java
│   └── pom.xml
│
├── aegis-runtime/                   # Process execution + checkpointing
│   ├── src/main/java/com/aegisos/runtime/
│   │   ├── ProcessRuntimeAgent.java
│   │   ├── AegisJob.java            # User-facing interface
│   │   ├── JobContext.java
│   │   ├── JobExecutor.java
│   │   └── checkpointing/
│   │       ├── CheckpointManager.java
│   │       └── MigrationCoordinator.java
│   └── pom.xml
│
├── aegis-api/                       # Public API surface
│   ├── src/main/java/com/aegisos/api/
│   │   ├── AegisOS.java             # Main entry point for users
│   │   ├── ClusterInfo.java
│   │   └── ProcessManager.java
│   └── pom.xml
│
├── aegis-node/                      # Node bootstrap + wiring
│   ├── src/main/java/com/aegisos/node/
│   │   ├── NodeMain.java            # main()
│   │   ├── NodeConfig.java
│   │   └── NodeWiring.java          # Dependency injection / setup
│   └── pom.xml
│
├── aegis-cli/                       # Command-line interface
│   ├── src/main/java/com/aegisos/cli/
│   │   ├── AegisCLI.java
│   │   └── commands/
│   │       ├── StartCommand.java
│   │       ├── PutCommand.java
│   │       ├── GetCommand.java
│   │       ├── RunCommand.java
│   │       └── NodesCommand.java
│   └── pom.xml
│
├── aegis-test-cluster/              # Integration test harness
│   ├── src/test/java/com/aegisos/cluster/
│   │   ├── ClusterHarness.java      # Spin up N nodes in-process
│   │   ├── Phase1Test.java
│   │   ├── Phase2Test.java
│   │   └── ...
│   └── pom.xml
│
├── docs/
│   ├── DetailAndPlanning.md         # This file
│   ├── RAFT_NOTES.md
│   ├── GOSSIP_NOTES.md
│   └── SECURITY_MODEL.md
│
└── pom.xml                          # Parent POM (Maven multi-module)
```

---

## 9. Key Algorithms & Protocols

### Gossip Convergence

With N nodes, each gossiping to K peers every T seconds, information spreads in O(log_K(N) × T) time. For N=100, K=3, T=1s: convergence in ~5 seconds.

**Dead node detection:** A node is marked SUSPECT if not seen for `suspectTimeout = 3 × gossipInterval`. It's marked DEAD after `deadTimeout = 10 × gossipInterval`. These are tunable.

### Raft Safety Properties

Raft guarantees:
1. **Election Safety:** At most one leader per term
2. **Log Matching:** If two logs have the same index and term, they are identical up to that point
3. **Leader Completeness:** If a log entry is committed, it will be present in all future leaders' logs
4. **State Machine Safety:** All nodes apply the same sequence of commands

### Chunk Placement Strategy

When replicating a chunk to 3 nodes:
1. Never place two replicas on the same node
2. Prefer nodes in different network segments (if topology info available — future feature)
3. Prefer nodes with more free storage
4. Use consistent hashing (Kademlia) as a tiebreaker for replica assignment

### Checksums and Integrity

```
chunkId       = SHA-256(encrypted_chunk_bytes)
fileId        = SHA-256(filename || ownerNodeId || createdAt)
messageHmac   = HMAC-SHA256(sessionKey, header_bytes || body_bytes)
```

---

## 10. Testing Strategy

### Unit Tests
- `IdentityServiceTest` — key generation, sign, verify, NodeID derivation
- `MessageSerializerTest` — round-trip serialization of all message types
- `RaftLogTest` — append, commit, term checks
- `ChunkSplitterTest` — split + reassemble, various file sizes
- `PlacementAlgorithmTest` — scoring, edge cases (all nodes full, single node)
- `GossipProtocolTest` — merge logic, dead node detection

### Integration Tests (use `ClusterHarness`)
- **Phase 1:** Node A signs + sends → Node B verifies correctly
- **Phase 2:** 5-node cluster converges membership within 15 seconds
- **Phase 3:** Leader failure → new leader elected within 500ms; no data loss
- **Phase 4:** File survives N-1 node failures (where N is replication factor)
- **Phase 5:** 100 jobs submitted → all complete, results match expected
- **Phase 6:** Node killed mid-job → job completes on different node with correct result

### Chaos Tests
- Kill random nodes at random intervals
- Partition network (simulate two isolated halves)
- Inject random latency and packet loss
- Fill a node's disk and verify graceful handling

### Performance Benchmarks
- File write throughput: Target ≥ 50 MB/s for large files on LAN
- Job latency: Time from submission to execution start < 500ms under normal load
- Gossip convergence: 100-node cluster converges in < 10 seconds
- Raft throughput: ≥ 5,000 log entries/second on 3-node cluster

---

## 11. Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Raft implementation bugs (split brain) | High | Critical | Use a well-tested Raft library as reference (e.g., Atomix/Copycat); exhaustive unit tests; formal verification of state machine |
| Network partition causes data inconsistency | Medium | High | Raft's majority quorum ensures only one partition can make progress; AegisFS reads require quorum on metadata |
| Clock skew breaks timestamp-based replay protection | Medium | Medium | Use ±30s tolerance window; consider logical clocks (Lamport) for v0.2 |
| Checkpointing insufficient for complex job state | Medium | Medium | Document limitations clearly; initially support only serializable Java objects |
| Performance bottleneck at Raft leader | Medium | Medium | Raft leader handles all writes — batch commits, pipeline log entries, consider multi-Raft groups for v0.2 |
| Ed25519 key compromise | Low | Critical | Detect unusual behavior (impossible node IDs, replayed old messages); key rotation in v0.3 |
| Java GC pauses causing election timeouts | Low | Medium | Use ZGC (low-latency GC) in JVM flags; tune election timeout to be >> expected GC pause |

---

## 12. Success Criteria & KPIs

### v0.1 MVP Success (the bare minimum)

- [ ] 3+ nodes form a stable cluster
- [ ] Nodes communicate exclusively over encrypted, authenticated channels
- [ ] A file put on Node A can be retrieved from Node B
- [ ] Node failure detected within 10 seconds
- [ ] A file with replication factor 3 survives 1 node failure without data loss
- [ ] A job submitted to the cluster executes on the best-fit node and returns correct result
- [ ] System recovers without manual intervention after any single-node failure

### v0.1 Performance Targets

| Metric | Target |
|---|---|
| Cluster formation time (3 nodes) | < 5 seconds |
| File write (10 MB, 3 replicas, LAN) | < 5 seconds |
| File read (10 MB, local replica) | < 1 second |
| Job submission-to-start latency | < 500ms |
| Node failure detection time | < 10 seconds |
| Cluster recovery after node failure | < 30 seconds |

---

## 13. Open Questions & Future Work

### Open Questions for v0.1

1. **Serialization format:** Protocol Buffers vs. a custom binary format vs. Java's built-in serialization (PB is recommended for forward compatibility)
2. **First-contact trust:** Should new nodes be trusted automatically (TOFU) or require manual key exchange? TOFU is simpler for experimentation; manual is safer.
3. **Chunk size:** 1 MB default is a starting point. Should it be configurable per-file?
4. **Raft storage:** SQLite vs. append-only log file vs. RocksDB for Raft log persistence?
5. **Job class delivery:** How does the target node get the user's job class bytecode? Via AegisFS? Via the network message itself?

### Future Features (v0.2+)

- **ACL / Access Control:** Per-file and per-job permissions tied to NodeIDs
- **Multi-cluster federation:** Connect AegisOS clusters across WANs
- **Web dashboard:** Real-time cluster visualization
- **Distributed shell:** `aegis exec` — run a shell command on any/all nodes
- **Container execution:** Run OCI containers as AegisOS jobs
- **Edge computing:** Support for low-powered nodes with resource constraints
- **Key rotation:** Rotate Ed25519 keypairs without cluster disruption
- **Byzantine fault tolerance:** Replace Raft with BFT-Raft for hostile environments
- **Distributed package manager:** Install/update software across the cluster atomically

---

*Document last updated: AegisOS v0.1 Concept Phase*
*Maintainer: AegisOS Core Team*

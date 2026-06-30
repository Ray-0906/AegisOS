# AegisOS Strategic Roadmap (v2.x)

The v1.x architecture established the foundational distributed execution fabric, achieving spatial awareness, physical process supervision, and distributed stream multiplexing.

The v2.x roadmap is defined by a strict engineering hierarchy: Operability and Durability must precede Scale and Features. We will not expand the payload capabilities of the system until we have complete visibility into its operation and guaranteed state recovery for long-running computations.

## [COMPLETED] Phase 1: Operability (v2.1)
**The Visual Control Plane**
A headless orchestrator is a black box. We will build a comprehensive, real-time observability frontend to expose the internal state machine.
* [x] **Next.js Dashboard:** A real-time web interface consuming AegisOS REST endpoints.
* [x] **Dynamic Topology Mapping:** Visual representation of node health, Gossip telemetry, and Raft consensus stability.
* [x] **Gamified Progression UI:** Implementing dark-mode, high-contrast visual logic to map the lifecycles of distributed workloads into a rank-based progression system, turning cluster management into an engaging, reactive experience.

## [COMPLETED] Phase 2: Durability (v2.2)
**Stateful Checkpointing & Fault Tolerance**
Currently, a hardware failure during a long-running process results in total progress loss. We will enable true fault tolerance.
* [x] **Checkpoint API:** Fleshing out the `checkpoint()` hook in `LocalRuntimeEngine` to allow running workloads to flush their memory state safely to `AegisFS`.
* [x] **State Injection:** Upgrading the scheduler to automatically retrieve and inject saved state chunks into replacement nodes when recovering a `FAILED` process.

## [COMPLETED] Phase 3: Scale (v2.3)
**The Polyglot Engine**
AegisOS is currently constrained by a hardcoded Java runtime boundary. We will open the compute layer to the broader software ecosystem.
* [x] **Dynamic Execution:** Modifying `ArtifactRecord` to support custom execution commands.
* [x] **Binary Agnosticism:** Decoupling `LocalRuntimeEngine` from `java -jar` to natively support Python scripts, Node.js payloads, and compiled binaries directly on the host OS.

## [COMPLETED] Phase 4: Features (v2.4)
**Agentic Orchestration**
Moving beyond static script execution to orchestrating complex, decentralized workflows.
* [x] **Distributed State Graphs:** Evolving the scheduler to natively manage multi-step, cyclic execution paths.
* [x] **AI First-Class Citizens:** Allowing AegisOS to manage multi-agent systems, piping context between disparate nodes (e.g., a data-gathering node streaming state to an analysis node) using the Virtual IPC overlay.

## Phase 5: Operational Sustainability (The Lifecycle Engine)

**Strategic Objective:** AegisOS currently operates as an append-only system. To prevent catastrophic Out-Of-Memory (OOM) errors and disk exhaustion over prolonged uptimes, the cluster must autonomously manage the lifecycle and destruction of historical data.

### Epic 5.1: Raft State Snapshotting (Consensus Compaction)
* **The Risk:** The Raft consensus log continuously appends every state change. A node running for 30 days will have millions of log entries, consuming massive heap memory and making reboot recovery unacceptably slow.
* **The Architecture:** 
  * Implement snapshotting at the consensus layer.
  * When the log reaches a predefined threshold (e.g., 10,000 entries), the State Machine writes its current exact state to disk and drops all previous log entries.
  * Implement "InstallSnapshot" RPCs so new nodes joining the cluster can download the compressed state instead of replaying millions of obsolete commands.

### Epic 5.2: Distributed Garbage Collection (Disk Management)
* **The Risk:** Every uploaded polyglot script, `.jar` file, and `checkpoint.dat` memory dump remains in `AegisFS` and the local `logs/` directory forever.
* **The Architecture:**
  * Build a low-priority background daemon (The Scavenger) on each worker node.
  * Implement a TTL (Time-To-Live) or Reference Counting protocol. If an artifact has not been referenced by an active or recently submitted process in 7 days, the cluster unanimously agrees to purge the physical files from the distributed filesystem.

### Epic 5.3: Process Retention & Archival (Memory Management)
* **The Risk:** The `InMemoryProcessTable` stores every executed pipeline in active RAM. Heavy workflow submission will eventually crash the JVM.
* **The Architecture:**
  * Define a strict retention policy for `COMPLETED` and `FAILED` processes (e.g., retain for 24 hours).
  * Build an eviction pipeline that moves expired records out of the active concurrent map and either drops them permanently or serializes them to cold storage (disk) for historical auditing.

## [COMPLETED] Phase 6: Zero-Trust Security & Multi-Tenancy
**Strategic Objective:** Transition AegisOS from an open cluster where any connected CLI can execute arbitrary commands into a secure, identity-driven compute fabric.
* **Architecture Roadmap:** Implement cryptographic workload signing, Role-Based Access Control (RBAC) via the `IdentityService`, and strict node authentication to prevent rogue nodes from joining the consensus pool.

## [COMPLETED] Phase 7: Service Mesh (DNS Late-Binding)
**Dynamic Address Resolution**
* [x] **Service Maps:** Workloads can specify `serviceName` and route IPC via `pipeToService`.
* [x] **Late-Binding Engine:** The `LocalRuntimeEngine` actively queries the distributed `ProcessTable` to resolve arbitrary service names to actual physical process IDs precisely at runtime execution, allowing ephemeral topologies to self-organize without hardcoded IP addresses.

## [COMPLETED] Phase 8: Fortress (mTLS & Cryptographic Secrecy)
**Military-Grade Cluster Encryption**
* [x] **Zero-Config Certificates:** Auto-generation of 2048-bit RSA X.509 self-signed certificates across all peers using BouncyCastle.
* [x] **Permissive Trust:** Implemented a permissive application-layer `TrustManager` combined with AES-256 TLS 1.3 to ensure deep packet inspection immunity for all Gossip, Raft, and IPC traffic.
* [x] **Identity Delegation:** Ed25519 handshake validation handles node identity verification natively, avoiding standard PKIX pitfalls.

## [COMPLETED] Phase 9: The Vault (Distributed File System)
**Replicated Storage Backend**
* [x] **Chunk Scrubber & Anti-Entropy:** `AegisFS` handles real-time chunk replication and detects missing fragments across peers. 
* [x] **REST Integration:** Completely replaced CLI file capabilities with formal `/v1/files/*` endpoints per `INV-054`, enabling external application ingestion and verification.
* [x] **Fault Tolerance:** Proved resilient to ingestion-node catastrophic failure; chunks auto-replicate to remaining Raft voters seamlessly.

## [COMPLETED] Phase 10: The Panopticon (Distributed Observability)
**End-to-End Tracing**
* [x] **Distributed Edge Minting:** Job ingestion REST boundaries now immediately mint `TraceContextProto` (UUID-based Trace IDs) per request.
* [x] **Raft State Propagation:** Trace IDs ride along the consensus machine inside the `ProcessRecord` down to the metal execution layer.
* [x] **Observability Pump:** Trace identity is injected into every native lifecycle log stream (`Starting process...`, `Established IPC...`, `Exited with code...`), illuminating the runtime black box.
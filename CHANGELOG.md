# Release Notes - AegisOS v2.0.0 (The Polyglot Overlay Milestone)

AegisOS v2.0.0 introduces true polyglot capabilities, distributed Virtual IPC stream routing, and stateful checkpointing, transforming the runtime from a JVM-only supervisor into a completely generalized, language-agnostic distributed OS.

## Major Architectural Features

* **The Polyglot Engine:** `LocalRuntimeEngine` has been completely decoupled from the JVM. Through the `executionCommand` schema field, you can now orchestrate Python scripts, compiled C++ binaries, Node.js applications, and bash orchestration. AegisOS injects the local file path via an `{artifact}` token.
* **Virtual IPC Overlay:** Real Unix-style piping over a P2P network. `CompletableFuture.runAsync` aggressive standard output pumping eliminates OS buffer blocking risks. The network layer routes these bytes directly into the `OutputStream` of remote receiving processes via the `pipeToProcessId` schema extension.
* **Stateful Checkpointing:** The `InMemoryProcessTable` now acts as a distributed checkpoint registry. Payloads can emit a `CommandType.SAVE_CHECKPOINT`, persisting their internal state into the Raft log. On crash or restart, `LocalRuntimeEngine` fetches and injects the latest binary snapshot back into the workload.
* **Service Mesh (DNS Late-Binding):** Workloads can now route IPC traffic using abstract `serviceName`s. The `LocalRuntimeEngine` queries the distributed `ProcessTable` to dynamically resolve actual process IDs at runtime, bypassing hardcoded IP addresses.
* **Fortress (mTLS & Cryptographic Secrecy):** Zero-config auto-generation of 2048-bit RSA X.509 self-signed certificates via BouncyCastle. Gossip, Raft, and IPC traffic are now fully cloaked in AES-256 TLS 1.3 encryption, validated via the Ed25519 node identity handshake.
* **The Vault (Distributed File System):** Replaced CLI file capabilities with formal REST endpoints. `AegisFS` provides fault-tolerant chunking, background scrubbing, and anti-entropy to guarantee artifact persistence across the cluster, surviving immediate ingestion-node destruction.
* **The Panopticon (Distributed Observability):** Trace IDs are now minted at the REST ingestion edge and propagated via `TraceContextProto` into the Raft state machine. Trace identity (`[TRACE: <id>]`) is natively injected into all critical Java compute engine telemetry, illuminating the entire job lifecycle.
* **Consensus Compaction (Snapshotting):** Raft logs are now continuously compacted to prevent infinite disk and heap memory growth. The State Machine saves exact state binary snapshots and truncates obsolete log entries, while the `InstallSnapshot` RPC allows new followers to quickly synchronize with the leader without replaying historical logs.
* **Process Lifecycle Engine:** Hardened the `InMemoryProcessTable` with strict TTL eviction policies for `COMPLETED` and `FAILED` workloads, providing autonomous memory management and preventing JVM Out-Of-Memory errors over prolonged uptimes.
## Upgrading
All state from `v1.x` should be purged due to new schema requirements in `ProcessRecordProto` (`executionCommand`, `pipeToProcessId`). Start fresh clusters using `--bootstrap`.

---

# Release Notes - AegisOS v1.0.0 (The Distributed Execution Milestone)

AegisOS v1.0.0 marks the transition from a consensus experiment into a fully functional, self-healing distributed operating system runtime. This release introduces true spatial awareness, physical process supervision, and distributed stream multiplexing.

## Major Architectural Features

* **Reactive State Machine:** The `ProcessTable` now operates on a fully asynchronous, lock-free event bus. State transitions (`SUBMITTED` -> `PLACED` -> `RUNNING`) are processed via a strict single-threaded executor, eliminating Raft consensus bottlenecks and head-of-line blocking.
* **Cryptographic Node Identity:** The placeholder pseudo-identities have been eradicated. AegisOS now routes and schedules workloads using true 32-byte cryptographic hashes (`NodeId`) derived from Ed25519 public keys, preventing split-brain placement collisions.
* **Resource-Aware Scheduling:** The Scheduler natively protects the cluster from OS-level Out-Of-Memory (OOM) panics. Nodes dynamically measure physical JVM boundaries (`maxMemory`) and CPU limits, broadcasting capacity via Gossip heartbeats. Workloads exceeding available hardware are safely rejected.
* **Physical Process Supervision:** The `LocalRuntimeEngine` now spawns physical OS processes using `ProcessBuilder`. It includes a native Java 9+ Reaper that asynchronously tracks process exit codes and maps them to `COMPLETED` or `FAILED` states in the distributed log.
* **Topology Reconciler (Ghost Workload Sweeper):** A background daemon running exclusively on the Raft Leader continuously monitors cluster topography. If a node suffers a hard crash, the reconciler automatically detects the Gossip timeout and transitions orphaned workloads to `FAILED`.
* **Virtual IPC Overlay:** Distributed Standard I/O is now a reality. Process logs (`stdout` and `stderr`) are pumped into a Virtual Stream Overlay that chunks the data and multiplexes it over the existing TCP sockets, streaming live logs back to the submitting node without blocking Raft heartbeats.

## Upgrading
All state from `v0.x` should be purged. Start fresh clusters using `--bootstrap` to initialize the new cryptographic identity matrices and schema requirements.

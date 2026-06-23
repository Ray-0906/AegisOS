# Release Notes - AegisOS v2.0.0 (The Polyglot Overlay Milestone)

AegisOS v2.0.0 introduces true polyglot capabilities, distributed Virtual IPC stream routing, and stateful checkpointing, transforming the runtime from a JVM-only supervisor into a completely generalized, language-agnostic distributed OS.

## Major Architectural Features

* **The Polyglot Engine:** `LocalRuntimeEngine` has been completely decoupled from the JVM. Through the `executionCommand` schema field, you can now orchestrate Python scripts, compiled C++ binaries, Node.js applications, and bash orchestration. AegisOS injects the local file path via an `{artifact}` token.
* **Virtual IPC Overlay:** Real Unix-style piping over a P2P network. `CompletableFuture.runAsync` aggressive standard output pumping eliminates OS buffer blocking risks. The network layer routes these bytes directly into the `OutputStream` of remote receiving processes via the `pipeToProcessId` schema extension.
* **Stateful Checkpointing:** The `InMemoryProcessTable` now acts as a distributed checkpoint registry. Payloads can emit a `CommandType.SAVE_CHECKPOINT`, persisting their internal state into the Raft log. On crash or restart, `LocalRuntimeEngine` fetches and injects the latest binary snapshot back into the workload.

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

# AegisOS Internal Workings: Subsystem Mechanics

This document details the internal mechanics and subsystem interactions that power the AegisOS v1.x distributed execution fabric.

## 1. The Reactive State Machine (Consensus -> Daemons)
AegisOS abandons synchronous RPC polling in favor of a reactive event loop. 
* The `ConsensusModule` acts as the authoritative source of truth via the Raft protocol.
* When the Raft leader commits a `CommandType.UPDATE_PROCESS_STATE`, the local `InMemoryProcessTable` updates its ledger and immediately pushes the event to a `ProcessStateListener` bus.
* Background daemons (like `SimpleProcessScheduler` and `LocalRuntimeEngine`) are wired to this bus using a strict `SingleThreadExecutor`. This guarantees FIFO state transitions and completely decouples long-running physical operations from the high-speed consensus network.

## 2. Gossip-Piggybacked Telemetry (Discovery)
To achieve spatial and resource awareness without flooding the network with metrics APIs, AegisOS utilizes the existing Gossip protocol.
* Before a node broadcasts its heartbeat, a `ResourceMonitor` uses `java.lang.management.OperatingSystemMXBean` to capture true JVM memory bounds and CPU limits.
* This data is packaged into a `NodeResources` protobuf and attached directly to the `PeerEntry`.
* As the cluster gossips, the `MembershipList` naturally converges into a real-time, hardware-accurate map of the entire distributed fabric.

## 3. Physical Process Supervision (The Engine)
AegisOS does not simulate execution; it supervises actual OS processes.
* When `LocalRuntimeEngine` observes a workload assigned to its specific cryptographic `NodeId`, it materializes the physical binary from `AegisFS`.
* It invokes `ProcessBuilder` to spawn a new JVM.
* A native Java 9 `process.onExit()` asynchronous hook acts as **The Reaper**, capturing the OS-level exit code (0 for `COMPLETED`, non-zero for `FAILED`) and proposing the final state to Raft.
* To prevent race conditions during cancellations, a deterministic kill-set intercepts OS signals triggered by `process.destroyForcibly()`, preserving the strict `CANCELLED` state.

## 4. Virtual IPC Overlay (Networking)
To achieve distributed Standard I/O without causing Head-of-Line blocking on the Raft heartbeat threads, AegisOS implements a Virtual Stream Multiplexer over standard TCP sockets.
* The physical `Process.getInputStream()` is consumed by a background relay thread.
* The stream is chunked into discrete 64KB `IpcChunkProto` packets.
* These packets are interleaved with standard RPC and consensus traffic across the `NetworkLayer`.
* On the receiving end, a `VirtualInputStream` catches the `IPC_DATA` messages, queues the byte arrays in a `LinkedBlockingQueue`, and exposes a standard `java.io.InputStream` interface to the user's CLI, effectively creating a borderless UNIX pipe.

## 5. Topology Reconciliation (The Ghost Reaper)
To ensure the distributed state machine remains synchronized with physical reality, the cluster requires a garbage collector.
* The `TopologyReconciler` is a scheduled daemon that runs *exclusively* on the active Raft Leader.
* Every 5 seconds, it sweeps the `ProcessTable` for `RUNNING` or `PLACED` workloads.
* It cross-references the assigned node's identity against the `MembershipList`. If the node's status has decayed to `DEAD` via Gossip timeouts, the Reconciler forcefully emits a `FAILED` transition to the Raft log.
* This strictly prevents "Ghost Workloads" from persisting indefinitely after a catastrophic hardware failure.

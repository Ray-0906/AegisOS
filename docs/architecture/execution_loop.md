# The AegisOS Distributed Execution Loop

The v1.0.0 execution loop operates on a strictly decoupled, event-driven architecture to guarantee cluster availability and prevent resource exhaustion.

### 1. Topology & Telemetry (Gossip Layer)
Every node continuously monitors its physical JVM `maxMemory` and OS-level CPU availability via `ResourceMonitor`. This telemetry is appended to the `PeerEntry` and broadcasted via the Gossip protocol. The `MembershipList` becomes a real-time, cluster-wide hardware map.

### 2. Resource-Aware Placement (Scheduler Daemon)
When a user submits a workload, a `SUBMITTED` state is proposed to the Raft log. The `SimpleProcessScheduler` reacts to this state change:
1. It queries the `MembershipList` for all `ALIVE` peers.
2. It filters out any node whose physical `freeMem` is lower than the requested process memory.
3. If no nodes qualify, it proposes a `FAILED` state to protect the cluster from an OOM crash.
4. If valid candidates exist, it randomly selects a node's cryptographic `NodeId` and proposes the `PLACED` state.

### 3. Physical Materialization (Runtime Engine)
The `LocalRuntimeEngine` on the target node intercepts the `PLACED` state.
1. It queries `ArtifactRegistry` for the filesystem metadata.
2. It calls `ArtifactCache.resolve()`, which fetches the binary chunks over the network from `AegisFS` and reassembles them on the local disk.

### 4. Process Supervision & IPC
1. The engine spawns a physical JVM using `ProcessBuilder`, linking it to the local `.jar`.
2. A background thread captures `getInputStream()` and chunks the standard output into `IpcChunkProto` packets.
3. These packets are multiplexed over the `NetworkLayer` TCP sockets back to the `submitterNodeId`.
4. A Java 9 `onExit()` hook waits for the OS process to die, at which point it proposes `COMPLETED` or `FAILED` back to the Raft log.

### 5. The Ghost Reaper
If the executing node suffers a catastrophic failure, the `TopologyReconciler` daemon (running exclusively on the Raft Leader) detects the Gossip timeout and forcefully emits a `FAILED` state, preventing orphaned "Ghost Workloads" from polluting the system.

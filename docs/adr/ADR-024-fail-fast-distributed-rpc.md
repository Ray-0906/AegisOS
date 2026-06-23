# ADR-024: Fail Fast Distributed RPC

## Context
During stabilization of AegisOS `v1.2-dev`, specifically within the chaos testing sprint, we observed several instances of the system stalling for long durations (15-30 seconds). Investigation revealed that these were not true network delays, but rather "zombie RPCs" routing to nodes that were already dead or connections that were already dropped. 

Because `CompletableFuture`s were orphaned and allowed to block until their absolute TTL expired, critical execution loops (such as checkpoint persistence, quorum replication, and job state updates) were unnecessarily blocked, causing cascading timeouts in deterministic test environments. 

We discovered that all of these issues were fundamentally liveness bugs, not timing bugs. To prevent these from recurring, we establish strict invariants for distributed RPC interactions across all subsystems.

## Decision

### Rule 1: Never wait on an entity already known to be dead.
Any subsystem initiating an RPC must immediately propagate failure if the target node is known to be dead. 
*   If a node is marked `DEAD` in the discovery registry, do not attempt to send the request.
*   If the underlying TCP connection closes while a request is in flight, the pending future must immediately be completed exceptionally (`IOException: Connection closed`). 

### Rule 2: Retries belong to the caller cadence.
Subsystems that provide distributed coordination (`ConsensusModule`, `NetworkLayer`, `AegisFS`) must **fail fast**. They must not wrap network calls in internal `Thread.sleep()` or retry loops.
*   **Bad**: `ConsensusModule` internally retrying `CLIENT_COMMAND` on failure.
*   **Good**: `ProcessRuntimeAgent` deferring a failed checkpoint to its next periodic tick.
*   **Good**: `RepairScheduler` re-evaluating a failed repair during its next cycle.

### Rule 3: Never leave pending futures orphaned.
When ownership boundaries disappear (e.g. connection closes, node dies, leadership is revoked), any futures waiting on those operations must be terminated. 
*   A pending RPC must fail if the connection drops.
*   A pending log entry must fail if the node loses leadership before committing it.

## Status
Accepted.

## Consequences
*   `NetworkLayer` now actively tracks pending requests by target `NodeId` and forcefully fails them upon connection closure.
*   `ChunkReplicator` fails fast if `discovery.statusOf(target) != ALIVE`.
*   Callers of synchronous `.get()` APIs must be prepared to catch `IOException` or `TimeoutException` immediately, and must defer retries to their own natural execution cadence rather than tight-looping.

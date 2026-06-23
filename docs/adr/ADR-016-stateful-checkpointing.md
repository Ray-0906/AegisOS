# ADR-016: Stateful Checkpointing

## Status
Accepted

## Context
AegisOS required a mechanism to recover from critical failure modes (e.g., node deaths, network partitions). Without persistent checkpoints, any interrupted job would be forced to restart execution from scratch, wasting compute cycles and risking non-convergence for massive payloads. The Raft state machine needed a generalized mechanism to ingest, replicate, and serve arbitrary binary checkpoint snapshots.

## Decision
We implement a unified Checkpoint Storage pipeline inside the State Machine application layer.
1. The `CommandType.SAVE_CHECKPOINT` command triggers the persistence of `CheckpointStateProto` data.
2. The `InMemoryProcessTable` manages a concurrent storage map mapping a `processId` to its most recent binary state checkpoint `byte[]`.
3. During startup, the `LocalRuntimeEngine` queries the persistent State Machine for the latest execution checkpoint via `ProcessTable.getLatestCheckpoint(processId)`.
4. If a checkpoint exists, it is durably injected into the starting payload using standardized network injection mechanisms. 

## Consequences
- **Positive:** Job execution can gracefully survive abrupt cluster failures by resuming at the last saved snapshot, preserving monotonicity and compute progress.
- **Positive:** This natively supports complex long-running workloads, such as distributed training loops and heavy data processing.
- **Negative:** Memory pressure on the `InMemoryProcessTable` scales with the size and frequency of checkpoints across the cluster. A durable disk spillover strategy or a strictly bounded checkpoint size limit may be required in future architectural iterations.

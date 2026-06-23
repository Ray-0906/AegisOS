# Observability Gap Audit

Before implementing new wait primitives in `ClusterAwaiter`, we must ensure they wrap clean, public accessors on `ClusterHarness` rather than reaching deep into subsystem internals (the "God object" anti-pattern).

This audit evaluates the four proposed awaiters against the current `ClusterHarness` API.

## Audit Matrix

| Proposed Awaiter | Can `ClusterHarness` expose it today? | If no, where is the source of truth? | Add to `ClusterHarness`? |
|------------------|--------------------------------------|---------------------------------------|--------------------------|
| `awaitCheckpointCreated` | **NO**. Harness only exposes `getJobState()` and `isJobPresent()`. | `JobRegistry` via `leader.runtimeAgent().registry().getCheckpoint(jobId)` | **YES**. Add `Long getCheckpointSequence(String jobId)` |
| `awaitArtifactReplication` | **NO**. `isArtifactReplicated()` only checks metadata registry, not physical chunk RF=3 status. | `AegisFS` and `DiscoveryManager`. Tests currently reach into `leader.fileSystem().fileIndex()` and cross-reference with `membership()`. | **YES**. Add `int getAliveChunkHolders(String fileId)` |
| `awaitRepairCompletion` | **NO**. | `RepairTaskStore` via `leader.fileSystem().repairTaskStore().hasPendingRepair(chunkId)` | **YES**. Add `boolean hasPendingRepair(String chunkId)` |
| `awaitSnapshotIndex` | **NO**. | `RaftLog` via `leader.consensus().raftNode().raftLog().snapshotIndex()` | **YES**. Add `long currentSnapshotIndex()` |

## Architectural Analysis

The current chaos tests (like `LongRunningCheckpointChaosTest`, `RepairLeaderFailoverTest`, and `LogTruncationVerificationTest`) are directly violating encapsulation.

For example, to check the snapshot index, tests currently do:
```java
leader.consensus().raftNode().raftLog().snapshotIndex()
```

To check repair completion:
```java
newLeader.fileSystem().repairTaskStore().hasPendingRepair(chunkId)
```

To check physical chunk replication:
```java
FileMetadata meta = leader.fileSystem().fileIndex().byName(path).get();
for (ByteString holderBytes : meta.getChunks(0).getNodeIdsList()) {
    if (leader.discovery().membership().statusOf(NodeId.of(holderBytes)) == ALIVE) ...
}
```

## Recommended Next Step (PR 1)

To strictly follow the `1 awaiter, 1 test, 1 stress verification` rule, the immediate next PR should be:

1. **Add to ClusterHarness**: `public Long getCheckpointSequence(String jobId)`
2. **Add to ClusterAwaiter**: `public void awaitCheckpointCreated(String jobId, int minSequence)`
3. **Refactor**: Migrate `LongRunningCheckpointChaosTest` to use the new awaiter, removing all raw registry lookups and Thread.sleeps.
4. **Stress Verify**: Run `stress-suite.ps1` with `LongRunningCheckpointChaosTest` 100x.

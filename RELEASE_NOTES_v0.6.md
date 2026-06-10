# AegisOS v0.6 Release Notes

**Release Date:** June 2026
**Theme:** Production-Grade Persistence Layer

The v0.6 release marks a major architectural milestone for AegisOS. Prior to this release, node correctness depended heavily on replaying history. With the completion of Sprint 6, correctness now relies on robust state reconstruction. AegisOS has shifted from a volatile distributed state machine to a hardened, persistent storage platform.

## Major Architectural Additions

### 1. Unified State Machine Orchestration (`ClusterStateMachine`)
We successfully refactored the Raft log application layer to eliminate a "God Object" anti-pattern. Subsystems such as `FileIndex`, `JobRegistry`, `RepairTaskStore`, and `ArtifactRegistry` now implement the `SnapshotParticipant` interface. The `ClusterStateMachine` acts purely as an orchestrator, dispatching serialization and deserialization requests to the respective participants.

### 2. Protobuf-Based Snapshot Serialization
All ad-hoc serialization methods have been replaced by a strictly versioned Protobuf schema for snapshots (`SnapshotFile`). The schema embeds metadata (Index, Term, Version) and distinct `SnapshotComponent` entries per registered subsystem.

### 3. Log Compaction and Snapshot Triggers
`RaftNode` now automatically truncates the replicated log once a configured entry threshold is reached. The node captures its local state to disk via the `ClusterStateMachine`, updates its `baseIndex`, and reclaims disk space.

### 4. InstallSnapshot RPC
We implemented the Raft `InstallSnapshot` RPC. If a follower falls so far behind that it requests a log index the leader has already compacted, the leader will bypass `AppendEntries` and stream the latest `SnapshotFile` chunk to the follower, allowing it to rehydrate its state machine from scratch and jump immediately to the leader's current index.

### 5. Atomic File Operations
Snapshot creation and installation both utilize atomic rename semantics (`tmp -> validate -> snapshot.dat`) to prevent corruption during power loss or abrupt process termination.

### 6. Soak Testing Infrastructure
A standalone CLI tool `SoakTestRunner` was introduced to execute long-running validation tests decoupled from standard Maven test phases, allowing us to subject the cluster to prolonged background job and file-write loads.

## New APIs

- **SnapshotParticipant**: Interface for registering subsystems with the `ClusterStateMachine`.
  - `String id()`
  - `byte[] snapshot()`
  - `void restore(byte[] data)`
- `/raft/metrics`: HTTP endpoint now exposes:
  - `snapshotCreatedCount`
  - `installSnapshotSentCount`
  - `installSnapshotReceivedCount`
  - `lastSnapshotDurationMs`

## Breaking Changes
- Node startup now requires a valid snapshot file or an empty log; it can no longer infer state purely from in-memory objects on reboot.
- `AegisNode` startup sequence refactored to prioritize snapshot loading before applying residual log entries.
- Configuration parameter `snapshotEntryThreshold` added (default: 1000).

## Known Limitations
- Snapshots are written as single, monolithic Protobuf files. If snapshot sizes exceed several hundred megabytes in the future, streaming file formats (like chunked zip/tar) or parallel chunked writes may become necessary. For now, the monolithic file simplifies checksum validation and versioning.
- The `ResourceAllocator` must manually resync with the `JobRegistry` during snapshot recovery; memory/CPU limits are re-checked against active jobs, which briefly delays full readiness by a few milliseconds.
- Currently, `InstallSnapshot` sends the entire monolithic snapshot as one payload. In very large states, this RPC might need to be fragmented.

## Test Counts & Verification
- **Total Tests:** 52
- **Pass Rate:** 100% (0 skipped)
- **Key Validation Tests Added:**
  - `CorruptSnapshotRecoveryTest` (ensures fallback/crash safety)
  - `ResourceAllocatorSnapshotRecoveryTest`
  - `InstallSnapshotVerificationTest`
  - `SnapshotStressCompactionTest`
  - `LogTruncationVerificationTest`

## Performance Observations
- **Snapshot Serialization Duration:** <10ms for nominal cluster states.
- **Node Restart Latency (Compacted Log):** ~200-250ms (measured after sustained load).
- **Log Compaction Overhead:** Minimal; Raft `AppendEntries` latency remains largely unaffected during background snapshotting.

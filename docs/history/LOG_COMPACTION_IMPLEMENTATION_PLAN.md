# Log Compaction Implementation Plan

This document outlines the design and state transitions for Raft Log Compaction and Snapshotting in AegisOS.

## 1. Storage

Log compaction reclaims disk space by discarding log entries that have already been applied to the state machine and safely stored in a snapshot.

### Triggering a Snapshot
- **Condition:** A snapshot is triggered when the number of un-compacted log entries (`commitIndex - raftLog.snapshotIndex()`) reaches the `snapshotEntryThreshold` (e.g., 1000). (Size-based triggers are omitted for simplicity and determinism).
- **Execution:** 
  1. The Raft Leader (or any node) invokes `ClusterStateMachine.takeSnapshot()`.
  2. The state machine queries all registered `SnapshotParticipant`s to serialize their current state.
  3. A `SnapshotFile` protobuf is assembled, containing the serialized components, a CRC32 checksum, and the index `N` (`lastIncludedIndex`) and term (`lastIncludedTerm`) of the snapshot boundary.

### Compaction Execution
Data durability must be guaranteed *before* truncating the log.

- **Durability Sequence:**
  1. Write snapshot data to `snapshot.tmp`.
  2. Call `fsync` on the file (ensure bytes are physically on disk).
  3. Atomic rename to `snapshot.bin`.
  4. Call `fsync` on the parent directory (ensure the rename is durable).
- **Log Truncation (Delete):**
  Only after the snapshot is durable do we call `raftLog.truncatePrefix(N, lastIncludedTerm)` to discard entries `<= N`.
- **Keep:**
  `lastIncludedIndex` (N) and `lastIncludedTerm` are stored in `snapshot-meta.bin`.

## 2. Catch-up (InstallSnapshot)

When a follower falls significantly behind, the leader may have already discarded the log entries the follower needs.

### Condition
- **When:** `nextIndex <= raftLog.snapshotIndex()`
- **Action:** Leader sends `InstallSnapshot` RPC instead of `AppendEntries`.

### State Transitions (Follower receiving InstallSnapshot)
1. **Validation:** Follower validates the leader's term. If `lastIncludedIndex <= commitIndex`, the snapshot is stale and is ignored.
2. **State Restoration:** Follower calls `snapshotLoader.load(snapshotBytes)`.
3. **Storage Durability:** The `SnapshotFile` is written to `snapshot.bin` atomically (with `fsync`s).
4. **Log Modification (Suffix Preservation):**
   - **If** the follower's log contains an entry at `lastIncludedIndex` whose term matches `lastIncludedTerm`:
     - Retain all log entries following `lastIncludedIndex` (the suffix).
   - **Else**:
     - Discard the entire log.
   - Update `snapshotIndex = lastIncludedIndex` and `snapshotTerm = lastIncludedTerm`.
5. **Pointer Update:** Follower sets `lastApplied = lastIncludedIndex` and `commitIndex = Math.max(commitIndex, lastIncludedIndex)`.

### State Transitions (Leader receiving InstallSnapshotResponse)
- Upon success, the leader updates the follower's `matchIndex` to `lastIncludedIndex` and `nextIndex` to `lastIncludedIndex + 1`.

## 3. Persistent Raft Metadata & Recovery

To recover correctly, we must understand the lifecycle of Raft pointers:
- `currentTerm` and `votedFor` are explicitly persisted in `meta.properties`.
- `commitIndex` is **NOT** persisted. In Raft, `commitIndex` starts at 0 (or `snapshotIndex`) on restart and is dynamically advanced by the leader.

### On Restart Sequence
1. **Load Metadata:** Load `currentTerm` and `votedFor` from `meta.properties`.
2. **Load Snapshot:** If `snapshot.bin` exists:
   - Parse `SnapshotFile` and call `snapshotLoader.load()`.
   - Set `lastApplied = snapshot.lastIncludedIndex`.
   - Set `commitIndex = snapshot.lastIncludedIndex`.
3. **Load Remaining Log:** `RaftLog` reads `snapshot-meta.bin` and any remaining uncompacted entries from `log.bin` into memory.
4. **CRITICAL CORRECTION (DO NOT REPLAY UNCOMMITTED ENTRIES):**
   - The current AegisOS implementation incorrectly replays *all* entries on disk during startup (`replayCommitted()`).
   - On restart, uncompacted entries on disk may **never have been committed** (e.g., from a deposed leader).
   - Therefore, the node must **stop** at `lastApplied = snapshotIndex`.
   - It must **not** automatically apply the remaining log entries. Instead, it waits for the active leader to send an `AppendEntries` (heartbeat) containing an updated `leaderCommit`. Only then does the node advance `commitIndex` and apply those surviving entries to the state machine.

## 4. Required Tests

Before implementing the core logic, we will build a test matrix to verify correctness.

### `LogCompactionTest`
- **Scenario:** Append 1000+ entries.
- **Assertion:** Snapshot is created, and the `RaftLog` prefix is physically truncated (disk size and entry count decrease).

### `RecoveryAfterCompactionTest`
- **Scenario:** Cluster runs, snapshot is taken, node is restarted.
- **Assertion:** State machine is correctly restored from the snapshot. Uncommitted suffix entries are NOT applied on startup until the leader confirms them.

### `InstallSnapshotAfterCompactionTest`
- **Scenario:** Take a follower offline. Leader compacts log past the follower's `nextIndex`. Follower rejoins.
- **Assertion:** Follower receives `InstallSnapshot`, correctly processes it, and successfully catches up to the cluster state.

### `SnapshotBoundaryTermTest`
- **Scenario:** Log is compacted at index `N`.
- **Assertion:** Calling `raftLog.termAt(N)` correctly returns `lastIncludedTerm` (read from `snapshot-meta.bin`), preventing `AppendEntries` prevLogTerm consistency checks from crashing.

### `InstallSnapshotSuffixPreservationTest`
- **Scenario:** Follower receives an `InstallSnapshot` that overlaps with its existing log (where `lastIncludedIndex` matches a committed entry in its log).
- **Assertion:** Follower preserves the suffix of its log rather than wiping it completely.

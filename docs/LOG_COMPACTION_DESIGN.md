# Log Compaction Design

## Objective
Prevent the Raft log from growing indefinitely by implementing Raft Snapshotting and Log Compaction in AegisOS.

## Background
Currently, AegisOS appends every cluster state change (ADD_VOTER, NEW_JOB, ARTIFACT_REGISTER) to the `RaftLog`. Upon restart, a node replays the entire log from index 1. Over time, the log will consume all available disk space, and node startup times will degrade to unacceptable levels. 

## Proposed Architecture

AegisOS will implement snapshotting as defined in the Raft consensus algorithm (Section 7).

### 1. Snapshot Generation (Leader & Followers)
* **Trigger:** When the `RaftLog` size reaches a predefined threshold (e.g., 100,000 entries or 50MB), the node will take a snapshot of its current state machine.
* **Snapshot Interface:** The `ClusterStateMachine` will implement a `SnapshotProvider` interface. Each sub-registry (`JobRegistry`, `ArtifactRegistry`, `ClusterConfiguration`) will serialize its current state to a binary stream.
* **Storage:** The snapshot will be written to disk (e.g., `raft/snapshots/snapshot-<lastIndex>-<lastTerm>.bin`).
* **Compaction:** Once the snapshot is safely on disk, the `RaftLog` will discard all log entries up to `lastIndex` (inclusive). The metadata file will be updated to point to the new `snapshotIndex`.

### 2. Snapshot Installation (Catching up lagging followers)
* **The Problem:** A leader might discard log entries that a very slow follower still needs to replicate.
* **InstallSnapshot RPC:** If `nextIndex` for a follower is less than the leader's `snapshotIndex`, the leader cannot send `AppendEntries`. Instead, it will send an `InstallSnapshot` RPC.
* **Flow:**
  1. Leader streams the snapshot binary file to the follower in chunks.
  2. Follower receives the snapshot, discards its entire current log (or the prefix up to the snapshot index), and applies the snapshot to its state machine.
  3. Follower updates its `commitIndex` and `lastApplied` to the snapshot's index.
  4. Leader resumes sending normal `AppendEntries` from `snapshotIndex + 1`.

### 3. State Machine serialization format
* To ensure safe and fast snapshotting, we will use Protocol Buffers (`snapshot.proto`).
* A unified `ClusterSnapshot` message will contain:
  * `ClusterConfigurationSnapshot` (current voters/learners)
  * `ArtifactRegistrySnapshot` (list of valid artifacts)
  * `JobRegistrySnapshot` (list of active/completed jobs and their final states)
* Since snapshots can be large, serialization will be streamed where possible, or chunked.

## Concurrency and Performance
* Taking a snapshot can be a slow I/O operation. It must **not block** the Raft consensus thread (`aegis-raft-sched`).
* **Copy-on-Write / State Forking:** The `ClusterStateMachine` must be able to produce a stable read-only view of its state (e.g., immutable collections) so that the snapshot can be serialized asynchronously to disk on a background thread while Raft continues to append new entries.

## Implementation Steps
1. Define `snapshot.proto` containing the state definitions.
2. Implement `takeSnapshot()` in `ClusterStateMachine`.
3. Add asynchronous log truncation in `RaftLog`.
4. Implement the `InstallSnapshot` RPC in `NetworkLayer` and `ConsensusModule`.
5. Add startup logic to load the latest snapshot before replaying the remaining log entries.

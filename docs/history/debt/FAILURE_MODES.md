# AegisOS Failure Modes & Mitigations

This document outlines known failure modes for AegisOS, their impact, and the systemic mitigations implemented (or planned) to handle them.

## 1. Node Process Termination
**Description**: A worker or leader node process is forcefully killed or crashes.
* **Impact**: Jobs running on the node halt. Chunks stored on the node are temporarily under-replicated. If it was a Raft leader, the cluster temporarily loses its ability to process state changes.
* **Mitigation**:
  * **Gossip Dissemination**: Surviving nodes mark the peer as `DEAD` after 2 consecutive failed gossip pings.
  * **Dynamic Quorum Shrinkage**: Raft recalculates the required majority size, excluding the `DEAD` node, to prevent the cluster from permanently stalling.
  * **Leader Failover**: If the leader died, a new leader is elected immediately from the remaining quorum.
  * **Self-Healing Storage**: The `SelfHealingReaper` kicks in after a delay, replicating missing chunks to restore the configured Replication Factor (`RF=3`).

## 2. Artifact Registry Split-Brain (Restart Desync)
**Description**: A node restarts and processes jobs before fully rebuilding its in-memory state.
* **Impact**: A client submits a job referencing an `artifactId`. The restarted node looks up the artifact, finds it missing (because it hasn't caught up to the latest Raft log), and fails the job with an `Unknown artifact` error.
* **Mitigation**:
  * **Raft Catchup Block**: Upon restart, nodes must apply all committed entries from the persistent Raft log before transitioning to `READY` status. Job processing is blocked until the `ArtifactRegistry` is fully reconstructed.

## 3. Storage Node Starvation (Simultaneous Node Deaths)
**Description**: Multiple nodes die simultaneously, reducing the total cluster storage nodes below the minimum Replication Factor (e.g., `RF=3`).
* **Impact**: Uploading new artifacts becomes impossible because there aren't enough storage targets to satisfy the replication requirement.
* **Mitigation**:
  * **Strict Quorum Enforcement on Writes**: `AegisFS.write()` blocks until `RF` targets are acquired. It strictly prevents partial, under-replicated writes, ensuring a client doesn't mistakenly believe a file is fully replicated.
  * **Client CLI Validation**: The CLI `put` command explicitly checks `storageNodeCount() >= 3` before attempting an upload, providing immediate user feedback instead of timing out silently.

## 4. Chunk Fetch Failure (In-Flight Node Death)
**Description**: A node attempts to execute a job and starts downloading chunks. The target peer serving the chunk dies mid-transfer.
* **Impact**: `SocketException: Socket closed` during chunk read.
* **Mitigation**:
  * **Replica Fallback**: `AegisFS.read()` keeps track of all `nodeIdsList` assigned to a chunk. If a fetch fails, it automatically falls back to querying the next alive peer in the list until the chunk is successfully retrieved.

## 5. Artifact Job Halting
**Description**: A worker node is killed in the middle of executing a long-running artifact job.
* **Impact**: The job stops. The Raft log forever lists the job as `RUNNING` on the dead node.
* **Mitigation (Current)**: None. The job is lost.
* **Mitigation (Planned for v0.3)**: Implement **Checkpoint Migration**. A supervisor process will detect jobs marked as `RUNNING` on nodes marked as `DEAD` via gossip. The cluster will reassign the job to a new worker. If the job supports checkpointing via the Aegis API, it will resume from the last saved state.

## 6. Network Partition
**Description**: A cluster of 5 nodes is partitioned into a group of 3 and a group of 2.
* **Impact**: The minority partition loses quorum and halts operations.
* **Mitigation**:
  * **Raft Quorums**: The majority partition (3 nodes) will elect a leader and continue accepting jobs and state changes.
  * **Eventual Consistency**: When the partition heals, the minority partition will discard uncommitted state and replay the AppendEntries from the majority leader, synchronizing their `ArtifactRegistry` and `JobRegistry`.

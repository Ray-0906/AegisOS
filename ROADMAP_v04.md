# AegisOS v0.4 Roadmap & Backlog

This document captures the highest leverage work for the v0.4 release. Having proven core correctness (storage integrity, consensus, runtime recovery, scheduler, process isolation), v0.4 shifts focus heavily toward operability, safety at scale, and observability.

---

## High Priority

### 1. Replica Metadata Pruning
We have rigorously proven that `SelfHealingReaper` correctly identifies missing/corrupt chunks and refuses to spread corruption. However, we need a robust, automated mechanism to actively prune dead replica references from the Raft `FileIndex`. 
- **Goal:** When a node permanently leaves the cluster, its chunk locations should be gracefully excised from the metadata to avoid unbounded accumulation of stale references.
- **Evidence:** Recent storage integrity tests demonstrate that missing replicas currently require explicit `REMOVE_REPLICA` commands before healing can proceed. We need a daemon to automate this.

### 2. Observability & Metrics
Currently, we rely heavily on log parsing (`nodeX.log`) to understand system behavior. We need a proper metrics subsystem.
- **Goal:** Expose real-time, queryable metrics.
- **Key Metrics to Track:**
  - `cluster_jobs_running`
  - `cluster_jobs_pending`
  - `cluster_cpu_allocated`
  - `cluster_memory_allocated`
  - `raft_commit_index`
  - `artifact_replication_factor` (health check across AegisFS)

### 3. Raft Snapshots / Log Compaction
The Raft log is currently an unbounded append-only file. As jobs execute and files are uploaded, this log will eventually exhaust memory/disk on long-running clusters.
- **Goal:** Implement Raft log compaction. Nodes should periodically snapshot the `ClusterStateMachine` state to disk and truncate the prefix of the Raft log.
- **Impact:** Drastically faster node restart times and bounded storage requirements for the control plane.

---

## Medium Priority

### 1. Backpressure & Flow Control
- **Goal:** Implement explicit backpressure on the `PeerConnection` layer. Prevent OOM scenarios when transferring large artifacts or during intense Raft log catch-ups.

### 2. Dynamic Membership (Joint Consensus)
- **Goal:** Formalize Raft membership changes using the Joint Consensus protocol. Currently, membership relies on a slightly static assumption based on gossip discovery. We need formal cluster reconfiguration to safely add/remove voters without split-brain risks.

---

## Out of Scope for v0.4 (Do NOT build yet)

Do not get distracted by the following features. They are strictly v0.5 or later, as they introduce massive complexity that is unnecessary until the core observability and compaction primitives exist.

- Containers / Docker / containerd integration
- Kubernetes integration / Operators
- Multi-tenancy & strict user isolation
- Fair scheduling / Priority queues
- GPU scheduling & specialized hardware affinities

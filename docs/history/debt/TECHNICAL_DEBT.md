# AegisOS Technical Debt & Known Issues

This document serves as the official registry of known issues, architectural limitations, performance baselines, and deferred infrastructure features for AegisOS. It provides the roadmap and context for v0.4 and beyond.

---

## 1. Critical Issues

### Replica Metadata Growth
* **Status:** Known
* **Severity:** Medium
* **Discovered:** v0.3 RC1 50-cycle Chaos Soak Test
* **Evidence:** Physical replica count remained correctly at `RF=3`, but `MetadataReplicas` count in the Raft state machine grew to `53`.
* **Root Cause:** The `SelfHealingReaper` correctly identifies lost replica chunks on dead nodes and triggers a repair to copy them to new healthy nodes. However, the dead node entries are never removed from the `FileMetadata` replica list in Raft.
* **Impact:** `FileIndex` metadata grows linearly with cluster membership churn.
* **Current Effect:** No immediate correctness impact, but causes unbound memory/storage overhead in the Raft state machine over long operational windows.
* **Planned Fix (v0.4):** Implement a replica metadata pruning mechanism to purge dead nodes from the metadata list once their replacement is successfully registered and verified.

---

## 2. Architectural Debt

### Job History Retention
* **Status:** Deferred
* **Severity:** Low
* **Evidence:** Heap memory grew consistently by `6-8 MB` per 1000 jobs during Phase A sequential runs.
* **Description:** Completed and failed jobs are retained in-memory inside the `JobRegistry` (`ConcurrentHashMap<String, JobRecord>`) indefinitely.
* **Current Impact:** Acceptable footprint for short-lived sessions or small job counts.
* **Future Risk:** Unbounded heap growth under high-throughput, long-running production workloads.
* **Planned Options:**
  - Introduce a configurable job retention window (e.g., purge completed records older than 24 hours).
  - Offload completed job records to a local persistent state or compact them out of memory.

### Artifact Cache Eviction
* **Status:** Deferred
* **Severity:** Low
* **Description:** The `ArtifactCache` resolves and stores local copies of JAR files, and `ArtifactClassLoader` isolates execution classpaths. Currently, there is no cache eviction policy or classloader lifecycle management.
* **Current Impact:** JAR files stay on disk, and loaded classes remain in memory.
* **Future Risk:** Local disk exhaustion and metaspace/heap bloat if hundreds of unique JARs are uploaded and run.
* **Planned Options:**
  - Implement Least-Recently-Used (LRU) artifact eviction.
  - Release references to `ArtifactClassLoader`s once terminal job execution is completed.

### Scheduler Polling Architecture
* **Status:** By Design (v0.3)
* **Severity:** Low
* **Evidence:** Median latency for `Accepted -> Assigned` is consistently `~2.2 seconds`.
* **Root Cause:** The scheduler evaluates state and places jobs on a periodic 2-second interval loop.
* **Current Impact:** Slower initial job execution start (average 2.2s overhead).
* **Planned Fix:** Transition from polling-based scheduling to an event-driven scheduler that triggers immediately upon a new `SUBMIT_JOB` command being applied to the Raft state machine.

### Worker IPC Protocol Maturity
* **Status:** Stable but Simple
* **Severity:** Low
* **Description:** The control socket handshake between `ProcessSupervisor` and `WorkerMain` uses plain TCP sockets and Java `Scanner` / `PrintWriter` to transmit JSON-like text strings.
* **Current Impact:** Hard to extend with rich metadata or bidirectional binary streaming.
* **Planned Options:**
  - Replace raw text-based socket protocol with structured Protobuf messaging over local sockets/pipes.

---

## 3. Performance & Benchmark Baselines

These verified numbers from the v0.3 RC1 Phase A benchmarks serve as the baseline to protect against future regressions:

* **Leader Failover Latency:** `~500ms` total service restoration time (Raft election completes in `~200-230ms`).
* **Worker Failover Latency:** `~15s` recovery time (includes Gossip detection, `LOST` transition in Raft, and rescheduling).
* **Scheduler Latency (1000 Jobs):** 
  - `Submit -> Accepted`: `<10ms` (highly efficient Raft log appends)
  - `Accepted -> Assigned`: `~2.2s` (polling interval dependent)
  - `Running -> Completed` (SleepJob 100ms): `~800ms` (includes process boot and IPC handshake)
  - `Throughput`: `~6.7 jobs/sec` (bounded by pool size and Sleep duration)
* **Memory Stability:** Stable linear growth of `6-8 MB` per 1000 jobs (retained protobuf metadata). No active leaks.

---

## 4. Infrastructure Debt

### Metrics & Observability
* **Status:** Partial
* **Current Metrics:** Nodes track only basic metrics (`cluster_jobs_running`, `cluster_jobs_pending`, `cluster_cpu_allocated`).
* **Missing:** Replication lag, scheduler queue latency, node heartbeat latency, and disk scrub times.
* **Planned:** Integrate a structured Prometheus metrics exporter or push gateway in v0.4.

### Raft Snapshots & Log Compaction
* **Status:** Not Implemented
* **Description:** Raft recovery relies entirely on replaying the log from index 0 on node restarts.
* **Impact:** Long recovery times, massive log file accumulation on disk, and memory bloat over time.
* **Planned Fix (v0.4):** Implement state machine snapshotting and log truncation once the log exceeds a configurable size.

### Rolling Upgrades
* **Status:** Not Supported
* **Description:** Modifying wire protocols or node versions currently requires taking the entire cluster offline.
* **Planned:** Version-check negotiation during handshakes to support minor-version mixed clusters.

### Authentication & Authorization
* **Status:** None
* **Description:** Any node/client on the network can query, submit jobs, or delete files. No cryptographic signature validation is performed on incoming commands.
* **Planned:** Implement client-side command signing using Ed25519 keys, verified by the Raft leader before commands are accepted.

### Multi-Tenant Isolation
* **Status:** JVM Isolated
* **Description:** Jobs run in isolated JVM processes, but there is no hardware-level or OS-level sandbox (e.g., CGroups, namespaces). A rogue job can consume host memory/CPU beyond its requested limits.
* **Planned:** Add optional CGroups limit enforcement on Linux nodes.

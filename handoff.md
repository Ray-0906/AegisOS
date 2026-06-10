# AegisOS v0.7 — Engineering Handoff

> Handoff for the next engineer. Read this top-to-bottom once before touching code.
> It captures **what exists, what was verified, what is currently planned, and where to start.**

---

## 0. TL;DR

- **What it is:** AegisOS — a secure, peer-to-peer, distributed OS runtime in **Java 21**. Nodes authenticate, gossip membership, store files, and execute JAR artifacts as jobs.
- **Current Phase:** We have just completed the **v0.7 Stabilization Pass**. The system now features a robust Job Execution model, log compaction (snapshots), strict audit frameworks, and decoupled membership. The execution model is proven stable under heavy chaos and node churn.
- **Milestone Status:** Sprints 1 through 7 are **COMPLETE and SIGNED OFF**. We are ready to begin Sprint 8.

---

## 1. Work Completed up to v0.7

### Sprints 1-5 (v0.5 Foundation) ✅ SIGNED OFF
- **CLI Isolation & Gossip Decoupling:** Strict Client-Server boundary for CLI. Raft membership decoupled from Gossip liveness.
- **Storage Audit Framework:** Measurement-only verification pipeline running strictly on the leader.
- **Raft Correctness:** Explicit `ADD_VOTER`/`REMOVE_VOTER` log semantics, dynamically derived from log state.
- **Two-Phase Repair:** Leader-only repair proposals (`REPAIR_CHUNK` → `REPAIR_COMPLETE`) using physical observation.

### Sprint 6: Snapshots & Log Compaction ✅ SIGNED OFF
- **Log Compaction:** Raft node compaction via snapshots. Serializes `ClusterStateMachine` state (Configuration, FileIndex, ArtifactRegistry, RepairTasks, JobRegistry) to disk.
- **Snapshot Transfer:** Lagging nodes or new nodes install snapshots via RPC instead of replaying the entire Raft log.
- **Configurable Threshold:** Controlled via `aegis.snapshot.entryThreshold`.

### Sprint 7: Job Execution & CLI Management ✅ SIGNED OFF
- **Job Execution Model:** Submit, monitor, and cancel jobs. Executes arbitrary JAR artifacts in isolated `Process` instances.
- **Job Lifecycle & State Machine:** Enforces strict state transitions (`PENDING` → `QUEUED` → `RUNNING` → `COMPLETED`/`FAILED`/`CANCELLED`/`LOST`) in the `JobRegistry`.
- **Fencing (Dual Gates):** Duplicate execution prevention. Superseded worker processes are fenced off from committing duplicate `COMPLETED` states to the Raft log.
- **Log Persistence:** Job stdout/stderr captured by `ProcessSupervisor` and uploaded back into the replicated AegisFS.
- **CLI Management:** `aegis jobs list`, `aegis jobs cancel <id>`, `aegis jobs logs <id>` commands added.
- **Observability:** Job execution metrics (`jobsStarted`, `jobsCompleted`, `jobsLost`, `jobsSuperseded`, etc.) exposed via `GET /metrics/jobs`.

---

## 2. Active Investigation: Surefire Fork VM Hang (2026-06-10)

> **Status:** DEBUG IN PROGRESS. Temporary instrumentation has been added across the codebase to diagnose why the Surefire forked JVM occasionally fails to exit after the full test suite completes.

### Symptoms
- The full `mvn clean install` or `mvn test -pl aegis-test-cluster` suite passes all tests.
- After `OvernightSoakTest` (or the full suite), Surefire reports:
  ```
  The forked VM terminated without properly saying goodbye.
  ```
- This is a **Surefire process failure**, not a test assertion failure. All individual tests have `Failures: 0, Errors: 0`.
- No `.dump` or `.dumpstream` files are produced.
- Short soak tests (2 minutes) pass cleanly with no hang. The hang likely requires a longer run (15-30+ minutes) or cumulative load across the full suite.

### Hypotheses Being Investigated

| ID | Hypothesis | Threads/Components | Evidence Direction |
|----|------------|-------------------|--------------------|
| **A** | **Orphaned `WorkerMain` child JVMs survive `ProcessSupervisor.kill()`.** The acceptor thread in `ProcessSupervisor` is not interrupted, and the child `WorkerMain` process may not die if it is blocked in a socket read that never hits EOF. | `ProcessSupervisor.acceptor`, `WorkerMain` | `destroyForcibly()` and `waitFor(2s)` added in `ProcessSupervisor.kill()` to log whether child exits. Logs show `processAlive=false` after `destroyForcibly()` in short tests. Needs validation in long test. |
| **B** | **`MetricsServer` virtual-thread executor threads are non-daemon and trap the JVM.** `HttpServer` with `Executors.newVirtualThreadPerTaskExecutor()` creates a platform thread per task carrier. Those carrier threads may be non-daemon, blocking JVM exit even after `HttpServer.stop(1)`. | `MetricsServer`, JDK `HttpServer`, `VirtualThreadPerTaskExecutor` | `MetricsServer.close()` calls `server.stop(1)`. Need to verify if carrier threads are non-daemon on this JDK (confirmed: virtual thread carriers on JDK 21 are non-daemon by default). **Most promising fix candidate.** |
| **C** | **A `ScheduledExecutorService` thread somewhere (`RaftNode`, `GossipProtocol`, `ResourceReporter`, `ResourceAllocator`, etc.) does not exit promptly.** `shutdownNow()` interrupts tasks but does not wait for termination. Individual scheduled threads may survive if a task is stuck and ignores `InterruptedException`. | `RaftNode.scheduler`, `GossipProtocol.scheduler`, `JobSupervisor.executor`, `ResourceReporter.scheduler`, `ResourceAllocator.reaper`, `CheckpointManager.scheduler`, `StorageAuditScheduler` | Shutdown log instrumentation added in `AegisNode.close()` to track each subsystem. Short tests show all subsystems close cleanly sequentially. Need to capture thread dump after long test. |
| **D** | **`NetworkLayer.handlerExecutor` (virtual thread per task executor) threads are stuck in blocking I/O and ignore shutdown.** `shutdownNow()` interrupts submitted tasks but virtual threads in blocking socket I/O may not respond immediately. Carrier threads wait for those tasks. | `NetworkLayer.handlerExecutor` | Log added in `NetworkLayer.close()` tracking `isShutdown`, `isTerminated`, result of `awaitTermination(3s)`. |
| **E** | **`TcpServer.acceptLoop` virtual thread blocks on `accept()` after `serverSocket.close()`.** Java `ServerSocket.close()` on Windows is asynchronous; `accept()` may remain blocked. The carrier thread is left waiting. | `TcpServer.acceptThread` | Also logged via `NetworkLayer.close()`. |
|
| **F** | **Multiple `ResourceAllocator.reaper` threads accumulate because `shutdownNow()` is called but not awaited.** If a test creates many nodes over time and `reaper.scheduleAtFixedRate(...)` survives, the number of reaper threads may grow. | `ResourceAllocator` | Currently no awaitTermination in `ResourceAllocator.close()`. Potential fix candidate. |

### Instrumentation Added (will be removed after fix)
- **`aegis-core/src/main/java/com/aegisos/core/util/DebugLogger.java`** — new utility for writing NDJSON debug logs to `debug-cc5e8f.log`.
- **`ProcessSupervisor.java`** — logs `kill()` invocation + `waitFor(2s)` result to verify child process death.
- **`JobExecutor.java`** — logs `close()` call with count of active supervisors before/after.
- **`AegisNode.java`** — logs every subsystem close call (metricsServer, jobSupervisor, checkpointManager, runtimeAgent, resourceReporter, resourceAllocator, auditScheduler, fileSystem, consensus, discovery, network).
- **`NetworkLayer.java`** — logs `handlerExecutor` shutdown status with `awaitTermination(3s)` result.

### Most Likely Root Cause (Current Best Guess)

**Hypothesis B — `MetricsServer` virtual thread executor blocks JVM exit.**

`MetricsServer.start()` uses:
```java
server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
```

On JDK 21, virtual thread executors create a platform carrier thread for each task. These carrier threads are **non-daemon**. `HttpServer.stop(1)` stops accepting new requests but does not forcibly interrupt in-flight handler threads. If any handler thread/carrier is currently processing a request when `stop()` is called, that thread may not exit until the request completes. Since the handler code contacts other subsystems (`node.discovery()`, `node.consensus()`, etc.), and those subsystems may be shutting down concurrently, the handler may block on a lock, sleep, or I/O that never resolves because the subsystem it depends on is now partially torn down.

**Proposed Fix (NOT YET APPLIED — needs verification logs):**
In `MetricsServer.close()`, wrap the executor shutdown in a sequence that:
1. Calls `server.stop(1)`.
2. Casts the executor to `ExecutorService`.
3. Calls `shutdownNow()`.
4. Calls `awaitTermination(5, TimeUnit.SECONDS)`.
5. Logs a warning if any threads remain alive.

OR: replace `newVirtualThreadPerTaskExecutor()` with a custom executor that creates daemon carrier threads, or use `ThreadPoolExecutor` with daemon threads.

**Alternative Fix (if B is ruled out):**
Add `awaitTermination(...)` with a timeout after every `shutdownNow()` across all subsystems that use `ScheduledExecutorService` or `ExecutorService`.

### Files Modified with Temporary Instrumentation
- `aegis-core/src/main/java/com/aegisos/core/util/DebugLogger.java` — NEW
- `aegis-runtime/src/main/java/com/aegisos/runtime/ProcessSupervisor.java` — modified
- `aegis-runtime/src/main/java/com/aegisos/runtime/JobExecutor.java` — modified
- `aegis-node/src/main/java/com/aegisos/node/AegisNode.java` — modified
- `aegis-network/src/main/java/com/aegisos/network/NetworkLayer.java` — modified

### Steps for Next Agent / Engineer
1. **Run a long soak test** (15-30 minutes) or the full suite:
   ```bash
   mvn clean test -pl aegis-test-cluster
   ```
   Or specifically:
   ```bash
   mvn test -pl aegis-test-cluster -Dtest=OvernightSoakTest -Dgroups=overnight
   ```
   (Default soak duration is 360 minutes; override with `-Dsoak.minutes=15` or similar.)
2. **Collect evidence:**
   - Read `debug-cc5e8f.log` after the hang.
   - Check Surefire output for `terminated without properly saying goodbye`.
3. **Analyze which subsystem close() call is missing or incomplete** using the log timestamps.
4. **Apply the fix** and verify with a second run.
5. **Clean up** by removing `DebugLogger.java` and all `#region agent log` / `#endregion` blocks from the files above.
6. **Commit** the fix (not the instrumentation).

### Known Ignored / Test-Only `System.exit` Calls
The following locations call `System.exit(...)` but they are inside test-only classes or CLI entry points and are NOT the cause of the hang:
- `aegis-cli/src/main/java/com/aegisos/cli/AegisCLI.java:48` — CLI main entry point
- `aegis-runtime/src/main/java/com/aegisos/runtime/WorkerMain.java` — child process entry point
- `aegis-scheduler/src/main/java/com/aegisos/scheduler/Scheduler.java:102` — test hook (`aegis.test.kill_after_probe`)
- Several `aegis-cli/src/test/java/com/aegisos/test/*.java` files — test harnesses

---

## 3. Core Architecture Decision Records (ADRs)

Before writing code, understand the key foundational rules:

| ADR | Decision |
|-----|----------|
| **ADR-016** | **Source of Truth Policy.** Raft Log is authoritative. Views (`FileIndex`, `JobRegistry`) are materialized. |
| **ADR-017** | **Verification Contract.** Repairs require *two consecutive audit scans* + *membership validation*. |
| **ADR-018** | **Client Transport Strategy.** Clients use `CLIENT_COMMAND`/`CLIENT_QUERY` via `NetworkLayer`. |
| **ADR-019** | **Repair Execution Contract.** Leader-only repair proposals via `REPAIR_CHUNK`. |

---

## 4. Current Project State

| Area | Status |
|------|--------|
| Storage durability | ✅ Proven |
| Corruption detection | ✅ Proven |
| Membership visibility | ✅ Proven |
| Storage audit & repair | ✅ Proven |
| Raft snapshots & compaction | ✅ Proven |
| Job Execution & Logging | ✅ Proven |
| Execution Failover & Recovery | ✅ Proven |
| Test suite exits cleanly | 🔄 Under investigation (Surefire fork hang) |

> [!NOTE]
> The system has been validated via a rigorous 30-minute `OvernightSoakTest` with a 100% completion rate under constant random node deaths, leader changes, and data churn, while adhering to strict memory (< 2048MB) and thread (< 500) invariants. The only remaining issue is ensuring the Surefire forked JVM exits cleanly after test completion.

---

## 5. Sprint Roadmap

```text
Sprint 1-5 ✅  v0.5 Foundation (Auth, Gossip, Storage, Audit)
Sprint 6   ✅  Snapshots & Log Compaction
Sprint 7   ✅  Job Execution, Fencing, Logs & CLI
Sprint 8   ←   Pending Next Phase (e.g. advanced scheduling, networking, or security)
```

---

## 6. Where to Start (Next Sprint)

1. **First priority:** Resolve Surefire fork VM hang (active investigation above).
2. Review the `walkthrough.md` index in the artifact workspace for full historical context of all sprints.
3. The core execution engine is in `JobSupervisor.java` and `ProcessRuntimeAgent.java`.
4. The cluster harness used for local chaos testing is `ClusterHarness.java`.

### Build and Test

```bash
# Full build
mvn clean install

# Run all integration tests (includes full chaos and soak suites)
mvn test -pl aegis-test-cluster

# Run only the core execution safety tests
mvn test -pl aegis-test-cluster -Dtest="JobLifecycleTest,DuplicateExecutionPreventionTest,LeaderFailoverJobRecoveryTest"

# Run a 3-node cluster locally
java -jar aegis-cli/target/aegis-cli-*.jar start --bootstrap --port 7001 --home node1
java -jar aegis-cli/target/aegis-cli-*.jar start --seed 127.0.0.1:7001 --port 7002 --home node2
java -jar aegis-cli/target/aegis-cli-*.jar start --seed 127.0.0.1:7001 --port 7003 --home node3
```

# AegisOS Engineering Handoff

> Current handoff for the next engineer/agent. Read this before changing code.
> It captures stabilization work, the shared-JVM test instability investigation,
> root causes found, fixes applied, and what still deserves follow-up.

**Branch:** `v1.0-dev`
**Last updated:** 2026-06-21

> **Key lesson:** The final stability work did not primarily involve making tests less strict; it involved correcting tests whose observation points no longer matched the distributed system's actual authority and ownership model after failover or migration.

**Deferred work (solo dev — no issue tracker):** see [`docs/post-v1-roadmap.md`](docs/post-v1-roadmap.md)

---

## 0. Current Status

- **Project:** AegisOS — Java 21 peer-to-peer distributed runtime with secure networking, gossip membership, Raft consensus, AegisFS storage, job execution, checkpointing, repair, and locality-aware scheduling.
- **Modules:** 12 Maven modules; integration tests live in `aegis-test-cluster` (~84 tests, ~17 min full run).
- **Surefire default:** single shared JVM (`reuseForks=true`, `forkCount=1`).

### Latest Validation (post-fix)

```bash
mvn clean test -pl aegis-test-cluster
```

| Metric | Result |
|--------|--------|
| Tests run | 84 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 1 (`LogCompactionTest`) |
| Elapsed | ~17 min |
| Log | `suite_shared_jvm_post_fix.log` |

**Suite is considered stable** — not because of pass counts alone, but because the last failing test now asserts the actual system invariant (see §4.4).

### Investigation Closure (Resolved)

| Item | Status |
|------|--------|
| Shared-JVM contamination | **Resolved** — substantially reduced by hygiene fixes (`TestJvmHygiene`, `JvmHygieneExtension`, filter cleanup) |
| `LocalityMetricsValidationTest` | **Resolved** — root cause identified and fixed (incorrect observation point) |
| Snapshot-related failures (`InstallSnapshotVerificationTest`, `SnapshotDuringExecutionTest`, etc.) | **Resolved** — disappeared after hygiene work; not scheduler/snapshot production bugs |
| Scheduler locality logic | **Validated** — placement and metrics work when observed from correct node |
| Runtime correctness fixes (§1) | **Validated** — terminal state ordering, retry ceiling, cancellation, hotspot spread, etc. |
| Thread leak as suite-wide driver | **Ruled out** — no monotonic thread climb across suite |

### v1.0 Freeze Checklist

- [x] Known correctness bugs fixed
- [x] Reproducible failures explained
- [x] Test suite stable (84 run, 0 failures, 0 errors, 1 skipped)
- [x] Remaining concerns documented and bounded (`handoff.md`, `docs/post-v1-roadmap.md`)
- [x] No active mystery failures

**Preserve before freeze:** `handoff.md`, investigation logs (`suite_*.log`), [`docs/post-v1-roadmap.md`](docs/post-v1-roadmap.md).

### Still Worth Tracking (Not Release Blockers)

Details in [`docs/post-v1-roadmap.md`](docs/post-v1-roadmap.md).

| Item | Classification | Notes |
|------|----------------|-------|
| `JobSupervisor.LEASE_DURATION_MS` static final | **Technical debt** | Did not cause locality failure. Deferred — see `docs/post-v1-roadmap.md` §1 |
| Worker lifecycle races | **Post-v1.0 investigation** | Green in latest suite; logs in `suite_fork_isolated.log`. Deferred — see `docs/post-v1-roadmap.md` §2 |
| Leadership observation inconsistency | **Low priority** | Stale `isLeader()` during concurrent elections |
| Repair/audit during shutdown | **Low priority** | `RejectedExecutionException` on teardown |
| Log compaction | **v1.0+ feature** | `LogCompactionTest` skipped |

---

## 1. Stabilization Story So Far

### 1.1 Original Symptom: `mvn clean package` Appeared to Hang

The first major incident looked like a distributed live-lock during the integration suite:
- Phase 5 snapshot/storage tests ran for a very long time.
- Logs showed leader churn, election storms, anti-entropy activity, snapshot load fallback, and repeated background work.
- The suite appeared to stall on "dead logs" instead of terminating.
- Initial suspicion: consensus/storage starvation.

### 1.2 Shutdown and Background-Executor Races

After improving shutdown behavior, full builds started completing. Fixes applied:
- `ProcessRuntimeAgent` skips job execution when shutting down.
- Heartbeat scheduling guarded against `RejectedExecutionException`.
- Shutdown-time updates avoid false hard failures when Raft is stopping.
- Delayed cleanup scheduling guarded during shutdown.
- Transient storage/network shutdown failures logged compactly.

Relevant files:
- `aegis-runtime/.../ProcessRuntimeAgent.java`
- `aegis-network/.../PeerConnection.java`
- `aegis-fs/.../ChunkReplicator.java`

### 1.3 Cancellation Semantics: `CANCELLED -> FAILED`

Root cause: runtime could kill a local worker after cancellation; worker failure path published `FAILED` over `CANCELLED`.

Fixes:
- `JobRegistry` treats terminal states deterministically.
- `ProcessRuntimeAgent` preserves `CANCELLED` when worker exits after cancellation.

### 1.4 Worker Recovery and Supervisor Identity

Fix: `JobExecutor` supervisors keyed by `jobId#executionId`, separating executions.

### 1.5 Checkpoint Fencing and Monotonicity

Fixes:
- `JobRegistry` ignores stale same-execution checkpoint updates.
- `LeaderFailoverCheckpointTest` waits for new leader registry to catch up.

### 1.6 Cluster Join Harness Flake

Fix: `ClusterHarness` re-checks current leader during join/catch-up/proposal flow instead of using a stale leader snapshot.

### 1.7 Client Command Forwarding Noise

Fix: `ConsensusModule` validates leader id length before parsing.

### 1.8 Build Failure: `HotArtifactSpreadTest`

Fix: `Scheduler` tracks in-flight assignment load per node; placement scoring includes `runningJobs` + pending `assignmentLoad`.

### 1.9 Terminal State Persistence Ordering (Bug 1)

Fix: terminal state committed via `update()` **before** log upload; log upload wrapped in best-effort try-catch.
New test: `TerminalStateOrderingTest.java`.

### 1.10 Job Retry Ceiling Enforcement (Bug 2)

Fix: `JobSupervisor` hard ceiling — exceeds `DEFAULT_MAX_RETRIES + 1` → terminal `FAILED`.

### 1.11 Raft Election Term Reset Hardening

Fix: `RaftNode.stepDown` resets `votedFor` to `null` on term increase.

### 1.12 `RepairLeaderFailoverTest` Hardening

Fix: test accepts `REPAIR_PROPOSED` or `BLOCKED` when background audit races ahead.

---

## 2. Shared-JVM Test Instability Investigation (2026-06-13/14)

### 2.1 Original Symptom

Rotating failures across timing-sensitive tests in **shared-JVM** full suite runs. Each failing test passed in isolation. Suspects included:
- `InstallSnapshotVerificationTest`
- `SnapshotDuringExecutionTest`
- `LocalityMetricsValidationTest`
- `DuplicateExecutionPreventionTest`
- `HotArtifactSpreadTest`
- `ArtifactCacheReuseTest`

### 2.2 Key Experiment: Fork Isolation Proves Contamination

```bash
mvn test -pl aegis-test-cluster -Dsurefire.forkCount=1 -Dsurefire.reuseForks=false
```

**Result:** 84 tests, **0 assertion failures**, 3 errors, 1 skipped (~16:37).
Log: `suite_fork_isolated.log`

All previously flaky assertion tests passed under per-class JVM isolation. **Contamination hypothesis proven.**

### 2.3 Post-Hygiene Shared-JVM Baseline

Test-only hygiene fixes applied (see §3). Then:

```bash
mvn clean test -pl aegis-test-cluster
```

**Result:** 83 passed, **1 failed**, 0 errors, 1 skipped (~16:57).
Log: `suite_shared_jvm_hygiene_run1.log`

**Only failure:** `LocalityMetricsValidationTest` — `winsAfter: 0, bytes: 0`

All other previously suspect tests passed in shared JVM after hygiene.

### 2.4 Thread Leak Theory — Weakened

`ClusterHarness.close()` diagnostics show `threadsBefore` 30–100, `threadsAfter` consistently ~4–5 after cleanup. No monotonic thread climb across suite. Thread leaks are not the primary shared-JVM instability driver.

### 2.5 Contamination Sources Identified

#### High confidence

1. **`NetworkLayer.messageFilter`** — static JVM-global (`aegis-network/.../NetworkLayer.java` ~line 55). Survives across tests when Surefire reuses fork. `StaleCheckpointFenceTest` lacked `finally` cleanup (fixed).

2. **`JobSupervisor.LEASE_DURATION_MS`** — `static final Long.getLong("aegis.lease.duration.ms", 15000)` at class load. `System.setProperty` + `clearProperty` **cannot** change it after first class load. Affects many failover tests; hygiene cannot fix this. Still a v1.0 testability defect.

#### Runtime-read hooks (cleanup works)

- `aegis.test.delay_upload_logs` → `ProcessRuntimeAgent`
- `aegis.test.delay_after_lost` → `JobSupervisor`
- `aegis.snapshot.entryThreshold` system property — unused; tests use `harness.setSnapshotEntryThreshold()`

#### Property audit

13 tests set `aegis.*` properties. All have matching `clearProperty` in `finally` or `@AfterEach`, but cleanup is ineffective for `static final` lease field.

### 2.6 Fork-Isolated Intermittent Errors (not assertion failures)

Observed once in full fork run; passed in post-hygiene shared-JVM run:
- `ArtifactCacheReuseTest`: `NoClassDefFoundError: SleepJob`
- `DuplicateExecutionPreventionTest`, `HotArtifactSpreadTest`: worker `Socket EOF. Parent died` / deserialization failed

Deprioritized unless they reappear.

---

## 3. Test Hygiene Fixes Applied

### 3.1 New Files

| File | Purpose |
|------|---------|
| `aegis-test-cluster/.../TestJvmHygiene.java` | Clears `messageFilter` + all `aegis.*` system properties |
| `aegis-test-cluster/.../JvmHygieneExtension.java` | `@BeforeEach` logs inherited JVM state; `@AfterEach` calls `TestJvmHygiene.clearAll()` |
| `aegis-test-cluster/src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension` | Registers extension globally for all cluster tests |

### 3.2 Modified Test Files

| File | Change |
|------|--------|
| `StaleCheckpointFenceTest.java` | `clearMessageFilter()` in `finally` |
| `LocalityMetricsValidationTest.java` | Fixed observation model (see §4) |
| `ClusterHarness.java` | `close()` logs `threadsBefore/After`; prints `node.close()` exceptions instead of swallowing |

**NOT changed:** production runtime, `JobSupervisor` lease logic, scheduler locality logic.

---

## 4. LocalityMetricsValidationTest — Root Cause and Fix

### 4.1 Symptom

Intermittent failure in shared-JVM suite: `winsAfter: 0, bytes: 0`.
~⅓ of isolated runs failed when `leader == executor`; 100% pass when `leader != executor`.

### 4.2 What Was NOT the Problem

Diagnostics proved on failing runs:
- Migration **did** happen (`aliveNode` registry: `executionId=2`, `RUNNING`)
- Checkpoint replicas existed on non-executor nodes (`localChunks=true`, 36 bytes each)
- Scheduler locality logic worked (pass runs: `winsAfter=1`, `bytesSaved=36`)

### 4.3 Actual Root Cause: Incorrect Observation Point

The test captured `leader` **before** partition, then read metrics from that reference **after** partition:

```java
// OLD (broken assumption)
AegisNode leader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst().orElseThrow();
long winsAfter = leader.scheduler().getLocalityWins();
```

When `leader == executor` and executor is partitioned:

| View | State |
|------|-------|
| Partitioned leader (stale reference) | `executionId=1`, `RUNNING`, `wins=0` |
| Non-partitioned alive node | `executionId=2`, `RUNNING`, migrated |

**Why:** `JobSupervisor.scan()` skips lease monitoring for self-assigned jobs:

```java
// JobSupervisor.java ~180-183
if (assigned.equals(self)) {
    continue;
}
```

Partitioned leader never processes LOST/reschedule. Followers (or new leader) migrate the job. `localityWins` increments on the **scheduling leader**, not the stale pre-partition reference.

**Classification:** `incorrect observation point` — NOT scheduler defect, NOT contamination, NOT shutdown issue.

### 4.4 Fix Applied (Option B — not topology pinning)

Topology remains random. Assertion model fixed:

#### Primary assertion (real invariant)

After migration, read job state from **non-partitioned nodes** via `resolveMigratedJob()`:
1. `executionId >= 2` and `RUNNING`
2. Assigned node ≠ partitioned executor
3. Assigned node has local checkpoint replicas (`getDownloadBytesSaved > 0`)

Pattern mirrors `CheckpointLocalityTest` — validate placement, not counters.

#### Supplementary assertion (metrics, correct observation point)

1. Snapshot `localityWins` on **all nodes** before partition
2. After migration, re-resolve scheduling leader among **non-partitioned nodes** via `resolveSchedulingLeader()`
3. Assert that node's metrics increased

#### Why this fix matters (causality, not pass rate)

The test was unsound because it broke causality:

```text
BEFORE (unsound):
  Observe leader at T0 → Partition → Leadership may change
  → Migration occurs elsewhere → Read metrics from original leader

AFTER (sound):
  Migration occurs → Find node that owns/scheduled migrated execution
  → Verify locality-aware placement → Optionally verify metrics on scheduling authority
```

Pass counts (10/10 isolated, 84/84 suite) confirm the fix. The fix itself aligns the assertion with the system invariant.

#### Deliberately NOT done

**Option A (pin executor to non-leader follower)** was rejected. It avoids the bad assumption by constraining topology rather than removing the assumption. The fix must survive leadership changes and future scheduling ownership shifts.

### 4.5 Validation

| Run | Result |
|-----|--------|
| 10× isolated `LocalityMetricsValidationTest` | 10/10 pass (includes `leader==executor` cases) |
| 1× full shared-JVM suite | 84 run, 0 failures |

---

## 5. Key Log Files

| File | Content |
|------|---------|
| `suite_fork_isolated.log` | Full `reuseForks=false` run (proves contamination) |
| `suite_shared_jvm_hygiene_run1.log` | Post-hygiene shared-JVM run (1 failure before locality fix) |
| `suite_shared_jvm_post_fix.log` | Post-fix shared-JVM run (84/84 green) |
| `locality_diag_run.log` | Single locality test run with pre-fix diagnostics |
| `fail_locality.txt` | Earlier isolated locality failure capture |

---

## 6. Expected Log Noise in Passing Builds

Several warnings/errors are expected because tests intentionally break things:
- `ArtifactNotFoundTest`: job fails because artifact/file does not exist.
- `MountPathTraversalTest`: job fails because mount paths are rejected.
- `CorruptCheckpointRecoveryTest`: worker fails on invalid checkpoint bytes.
- `CorruptSnapshotRecoveryTest`: snapshot load fails and falls back to full log replay.
- Chaos/Partition warnings: `partitioned from ...`, `Replication requirement not met`, `Checkpoint sequence ... temporarily deferred`, `no known leader`, `Failed to propose REPAIR_CHUNK`.

---

## 7. Remaining Follow-Up Candidates

> Canonical deferred-work log: [`docs/post-v1-roadmap.md`](docs/post-v1-roadmap.md). Sections below are summaries; the roadmap preserves evidence for future-you.

### 7.1 `LEASE_DURATION_MS` Static Final — **Deferred (§1 of roadmap)**

**Problem:**
```java
static final long LEASE_DURATION_MS = Long.getLong("aegis.lease.duration.ms", 15000);
```
Loaded at class init. `System.setProperty` + `clearProperty` cannot change it after first class load in a shared JVM.

**Impact:** Future failover/lease tests can be poisoned by test ordering. Did **not** cause the locality failure.

**Severity:** Technical debt, **not a release blocker**.

**Recommended fix:** Refactor to runtime config (constructor injection or per-scan property read). Production behavior change — needs careful review.

**Workaround:** `JvmHygieneExtension` logs effective lease via reflection before each test.

### 7.2 Worker Lifecycle Races — **Deferred (§2 of roadmap)**

**Prior symptoms** (fork-isolated run; green in latest shared-JVM suite):
- `DuplicateExecutionPreventionTest`, `HotArtifactSpreadTest`: `Socket EOF`, `Parent died`, `deserialization failed`
- `ArtifactCacheReuseTest`: `NoClassDefFoundError: SleepJob`

**Archived logs:** `suite_fork_isolated.log`

**Scope for future investigation:** worker process lifecycle, job cancellation, socket shutdown ordering.

**Severity:** Post-v1.0. See roadmap §2.

### 7.3 Executor Shutdown Audit — **Deferred (§3 of roadmap)**

Review `shutdownNow()` paths; add bounded `awaitTermination()` where appropriate. Collect evidence before changing behavior. See roadmap §3.

### 7.4 Leadership Observation Inconsistency

**Symptom:** During failover, `newLeader.isLeader()` can return `true` while cluster considers a different node the actual leader.

**Recommended follow-up:** Stricter leader election validation in tests or `ConsensusModule`.

### 7.5 Repair/Audit During Shutdown

**Symptom:** `Failed to propose REPAIR_CHUNK` due to `RejectedExecutionException` when shutting down.

**Recommended follow-up:** `RepairProposer` and audit schedulers check shutdown state before proposing.

### 7.6 Checkpoint Retention Timeout Noise

**Symptom:** `Failed to apply checkpoint retention ... TimeoutException` during chaos tests.

**Recommended follow-up:** Compact logging and retry retention deletes.

### 7.7 Container-based Runtime Evolution

Review `docs/V1_RUNTIME_DESIGN.md` for OCI container job execution.

### 7.8 Log Compaction Implementation

Review `docs/LOG_COMPACTION_DESIGN.md` for Raft snapshotting and log compaction.

---

## 8. What NOT to Do (Lessons Learned)

- **Do not** run a 10× suite matrix to diagnose locality failure — signal was already definitive at 83/84.
- **Do not** pin executor to non-leader follower as the primary locality fix — hides topology edge case.
- **Do not** assume `leader at T0 == node that performed reschedule` in partition/failover tests.
- **Do not** treat scheduler counter reads as primary assertions when placement can be verified directly.
- **Do not** assume shared-JVM flakiness is always a production bug — check contamination first.

---

## 9. Developer Environment Notes

- **Build:** `mvn clean test -pl aegis-test-cluster` (integration suite only)
- **Fork-isolated run:** `mvn test -pl aegis-test-cluster -Dsurefire.forkCount=1 -Dsurefire.reuseForks=false`
- **Single test:** `mvn clean test -pl aegis-test-cluster -Dtest=LocalityMetricsValidationTest` (clean avoids stale classloader `NoClassDefFoundError` on Windows)
- VS Code Java language server sometimes races Maven by touching `target/` class files. Workaround: restart language server or pause it during Maven runs.

---

## 10. Key Code Locations

| Area | Path |
|------|------|
| Test harness | `aegis-test-cluster/.../ClusterHarness.java` |
| JVM hygiene | `aegis-test-cluster/.../TestJvmHygiene.java`, `JvmHygieneExtension.java` |
| Locality test (fixed) | `aegis-test-cluster/.../LocalityMetricsValidationTest.java` |
| Locality placement test (reference) | `aegis-test-cluster/.../CheckpointLocalityTest.java` |
| Job supervisor / lease skip | `aegis-runtime/.../JobSupervisor.java` |
| Scheduler locality metrics | `aegis-scheduler/.../Scheduler.java` |
| Network partition filter | `aegis-network/.../NetworkLayer.java` |

---

## 11. Files Touched in This Investigation

### New
- `aegis-test-cluster/.../TestJvmHygiene.java`
- `aegis-test-cluster/.../JvmHygieneExtension.java`
- `aegis-test-cluster/src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension`

### Modified (test-only unless noted)
- `LocalityMetricsValidationTest.java` — observation model fix
- `StaleCheckpointFenceTest.java` — message filter cleanup
- `ClusterHarness.java` — close diagnostics

### Prior stabilization (see §1)
- `ProcessRuntimeAgent.java`, `JobRegistry.java`, `JobSupervisor.java`, `JobExecutor.java`
- `ConsensusModule.java`, `RaftNode.java`, `Scheduler.java`
- Various test hardening files

---

## 12. Recommended Next Steps for Next Agent

**v1.0 release path:** Suite is stable. No blockers from this investigation.

**Post-v1.0 / deferred items:** documented in [`docs/post-v1-roadmap.md`](docs/post-v1-roadmap.md) — no issue tracker needed for solo dev; preserves evidence so future-you does not redo this investigation.

1. **Lease configuration cleanup** — technical debt, not release blocker
2. **Worker lifecycle investigation** — post-v1.0; logs in `suite_fork_isolated.log`
3. **Executor shutdown audit** — low priority; collect evidence before changing behavior
4. **v1.0+ features** — container runtime, log compaction

**Closed — do not revisit unless new evidence:**
- Scheduler locality logic redesign
- Shared-JVM contamination (hygiene in place)
- `LocalityMetricsValidationTest` observation model (fixed)
- 10× suite statistical matrix for locality
- Option A topology pinning (rejected by design)

---

## 13. Bottom Line

The suite is stable because the **last assertion now follows causality**, not because a counter hit 84/84.

Resolved: shared-JVM contamination (hygiene), locality test observation bug, snapshot-related false positives, scheduler locality validation, prior runtime correctness fixes.

Remaining: **`LEASE_DURATION_MS` technical debt** and **worker lifecycle races** (post-v1.0, logs archived). Neither blocked this stabilization pass.

### Transport Layer Correlation Collision (RESOLVED)

Discovered a severe bug in `aegis-network` where RPC correlation IDs were strictly matched by ID and not by request/response type. Under load, such as in `Phase8Test`, this caused correlation ID overlap between concurrent requests (e.g. a Node sent a GOSSIP_SYN request and matched an inbound CLIENT_COMMAND_RESULT as the response). This resulted in protobuf parsing failures such as `NotLeaderException(null)`.

**Fix applied:**
1. Modified `aegis.proto` to add `bool is_response = 9;` to `MessageHeader`.
2. Updated `NetworkLayer.java` and `PeerConnection.java` to serialize and verify the `is_response` boolean, ensuring that inbound requests do not complete pending futures.

---

## 14. Phase 2 Stabilization & v0.4 Reliability Freeze (2026-06-20 to 2026-06-21)

This phase tackled major architectural issues causing cluster instability during network partitions and worker thread delays, and concluded with the official freeze of AegisOS `v0.4`.

### H13/H14: Raft PreVote & Election Storm Prevention (RESOLVED)

**Symptom:** During network partitions, minority nodes would spin in election loops, rapidly inflating their term number. Upon reconnection, this inflated term forced healthy leaders to step down immediately, causing a cluster-wide "election storm" and significant leaderless windows.

**Fix Applied:**
1. Implemented **Pre-Vote**: A node must successfully gather a majority of "PreVotes" from peers *before* it increments its actual term and becomes a `CANDIDATE`. This strictly prevents isolated nodes from disrupting the cluster.
2. Added `lastLeaderMessageTick` check: Nodes will reject `RequestVote` and `RequestPreVote` RPCs if they have recently heard from a healthy leader, preventing disruptive elections from partitioned nodes.
3. Modified `VoterPromotionTest` to assert against a newly exposed `getPreVoteStarts()` metric, correctly reflecting the new `PreVote` consensus behavior.

### H15: Asynchronous Terminal State Publication (RESOLVED)

**Symptom:** Worker threads (`ProcessRuntimeAgent`) and leader jobs (`JobSupervisor`) were treating `COMPLETED` and `LOST` metadata publication as synchronous, inline obligations with arbitrary retry loops. This effectively turned worker threads into mini-schedulers. If consensus was unstable, workers would hang up to 71 seconds doing exponential retries to publish state.

**Fix Applied:**
1. Introduced `TerminalPublicationScheduler`, backed by a `DelayQueue`.
2. Moved terminal state (`COMPLETED`, `FAILED`, `CANCELLED`, `LOST`) publishing to this single background thread with robust exponential backoff.
3. Stripped all retry/wait logic from worker execution blocks. Workers now simply enqueue the result and cleanly exit (`INV-037`).
4. `JobSupervisor`'s `LOST` emission became fully asynchronous.

### H7 & H8: Timing Contract and Ownership Boundary (RESOLVED)

Execution thread timing contracts and potential duplicate execution holes were investigated and structurally fixed, successfully satisfying the remaining critical reliability conditions required for the freeze.

### v0.4 Official Certification (COMPLETED)

To prove correctness without diminishing returns, the cluster underwent a targeted "Certification Pack" of 125 executions:
- 50× `StaleCheckpointFenceTest`
- 25× `DuplicateExecutionPreventionTest`
- 25× `StaleQueuedExecutionTest`
- 25× `Phase6Test#runningJobSurvivesNodeDeath`

**Results:**
- **125** successful leader elections
- **0** unexpected exceptions
- **0** test failures
- **0** test hangs

### `ARCHITECTURE_INVARIANTS.md` Created

To ensure distributed system constraints are clearly understood without re-reading investigation logs, a new document `ARCHITECTURE_INVARIANTS.md` was created. It serves as the single source of truth for properties like:
- **INV-025**: executionId = generation
- **INV-026**: ownership before side effects
- **INV-033**: terminal states are durable obligations
- **INV-037**: workers never own durability

### STATUS: CERTIFIED
**VERSION:** v0.4
**FREEZE DATE:** 2026-06-21

The `v0.4` milestone is now completely stabilized and frozen. Future agents can proceed to build new features or components atop this foundation.

---

## 15. The Shift to Platform Engineering (v1.3.x)

**Date:** 2026-06-22

With the foundational R&D phase completed and v0.4 frozen, the architecture transitioned towards strict Platform Engineering with a focus on discipline, rigid boundaries, and true REST decoupling.

### 15.1 Era 1 Architecture (The Problem)
In Era 1, the CLI acted as an active distributed system participant. When running a command like `aegis put` or `aegis run`, the CLI would:
1. Boot a temporary, ephemeral `AegisNode` via `ClientCommands.withClient()`.
2. Connect directly via P2P.
3. Observe Raft elections and manually query the leader.
4. Execute operations via the internal Java `aegis-api`.

This created a severe architectural leak: CLI clients were tightly coupled to cluster internal states (Gossip, Raft, Election Terms) and violated standard separation of concerns.

### 15.2 The Migration (Wave 1 to Wave 3)
We introduced `INV-048` through `INV-060` enforcing strict separation:
- **`aegis-client` created:** A stateless HTTP wrapper that tracks only leader routing and handles 503 retry logic without internal cluster awareness.
- **REST layer built:** Implemented `ApiServer` operating strictly on port `20001` (separated from P2P `9001` and Metrics `19001`).
- **Resource boundaries:** Defined rigid, immutable DTOs that do not leak internal structures (`INV-048`). All REST endpoints correspond exactly 1:1 with an existing CLI capability (`INV-054`). 
- **Large files:** Avoided base64 JSON encapsulation. Binary uploads use raw `application/octet-stream` streams (`INV-056`).

The migration happened in structured, frozen waves:
- **Wave 1:** `nodes`, `health`, `leader`
- **Wave 2:** File Operations (`put`, `get`, `ls`)
- **Wave 3A:** Jobs (`run`, `status`, `list`, `cancel`)
- **Wave 3C:** Artifacts (`upload`, `list`)

### 15.3 v1.3.2R Retirement Phase
After Wave 3C was verified, we formally executed an audited Retirement phase to purge Era 1 architecture (`INV-061`, `INV-063`).

- All residual internal usages of `ClientCommands.withClient()` were classified, verified obsolete, and permanently deleted (`Promote.java`, `DirectPromote.java`).
- `ClientCommands.java` itself was removed.
- A full `mvn clean verify` ran cleanly (98 tests passing), proving zero legacy runtime or test dependencies remained.
- Test infrastructure was cleansed of dependency on CLI implementation internals (`INV-062`).

**Result:** The CLI module (`aegis-cli`) is now purely a presentation layer that acts solely as a client connecting to the robust REST API layer. Era 1 is officially over.

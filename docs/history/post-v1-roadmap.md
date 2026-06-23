# Post-v1.0 Roadmap

> Lightweight deferred-work log for solo development. Not an issue tracker — a memory aid so future-you does not redo investigations already completed during the v1.0 stabilization pass.
>
> **Context:** `handoff.md` (full investigation), investigation logs (`suite_fork_isolated.log`, `suite_shared_jvm_hygiene_run1.log`, `suite_shared_jvm_post_fix.log`).

---

## 1. Lease configuration cleanup

**Status:** Deferred  
**Priority:** Medium  
**Release blocker:** No

### Current

```java
// JobSupervisor.java
static final long LEASE_DURATION_MS = Long.getLong("aegis.lease.duration.ms", 15000);
```

Read once at class load. `System.setProperty` / `clearProperty` in tests cannot change it after first load in a shared JVM.

### What we already proved

- Did **not** cause the `LocalityMetricsValidationTest` failure (that was an incorrect observation point — fixed).
- Suite is stable after hygiene + locality fix with current behavior.
- `JvmHygieneExtension` logs effective lease via reflection for diagnosis if ordering issues reappear.

### Risk if left alone

- JVM-order-dependent configuration in shared-JVM test runs.
- Hidden property poisoning when tests assume `aegis.lease.duration.ms` took effect.
- Difficult to write deterministic lease-timeout tests without fork isolation.

### Future work

- Move to injected config or runtime lookup (per-scan `System.getProperty`, or constructor-injected duration).
- Production behavior change — review carefully before merging.

---

## 2. Worker lifecycle hardening

**Status:** Deferred  
**Priority:** Medium  
**Release blocker:** No

### Observed (fork-isolated run only)

Intermittent errors — **not** present in latest shared-JVM suite (`suite_shared_jvm_post_fix.log`):

| Test | Symptom |
|------|---------|
| `DuplicateExecutionPreventionTest` | `Socket EOF`, `Parent died`, `deserialization failed` |
| `HotArtifactSpreadTest` | `Socket EOF`, `Parent died` |
| `ArtifactCacheReuseTest` | `NoClassDefFoundError: SleepJob` |

**Archived log:** `suite_fork_isolated.log`

### Areas to investigate (when revisiting)

- `CANCEL_JOB` ordering relative to worker teardown
- Socket shutdown sequencing (parent vs child)
- Worker termination protocol and message framing on abrupt exit
- Classpath visibility for dynamically loaded job classes (`SleepJob`) across fork boundaries

### What we already proved

- Not the cause of the final shared-JVM instability (83→84 failure was locality observation bug).
- Encouraging that post-hygiene shared-JVM run was fully green.
- Worth revisiting post-v1.0, not during freeze.

---

## 3. Executor shutdown audit

**Status:** Deferred  
**Priority:** Low  
**Release blocker:** No

### Observed

- `RejectedExecutionException` on `RepairProposer` / audit scheduler during node teardown
- `ChunkReplicator` / `ProcessRuntimeAgent` warnings during harness `close()`
- `ClusterHarness` now logs `threadsBefore` / `threadsAfter` — no monotonic leak across suite

### Future work

- Review all `shutdownNow()` paths across runtime, consensus, fs, network
- Add `awaitTermination()` with bounded timeout where tasks must drain
- **Collect evidence before changing behavior** — thread counts are already stable; avoid speculative shutdown refactors

### Related noise (low priority)

- `Failed to propose REPAIR_CHUNK` during shutdown
- Checkpoint retention `TimeoutException` in chaos tests

---

## 4. Post-v1.1 Roadmap

### v1.1 (Current - Feature Complete)
- ✅ Log compaction
- ✅ Worker lifecycle hardening
- ✅ RPC correlation isolation
- ✅ Dual runtime (JVM + Container)

### v1.15 (Next Sprint: Observability)
**Observability** is the highest ROI. Focus on stability and metrics:
- `JobTimeline`, `ClusterHealth`, `LeaderElectionMetrics`, `LeaseMetrics`, `RuntimeMetrics`, `ContainerMetrics`
- Expose `/metrics`, `/health`, `/jobs`

### v1.2 (Persistent Runtime Ownership)
Introduce `RuntimeRegistry` persisted in Raft.
This unlocks true container recovery:
`cluster restart` -> `reconstruct active runtimes` -> `attach/requeue deterministically`

### v1.3 (Advanced Container Features)
- CRIU checkpointing (true state resumption)
- Digest-based trusted image execution (`alpine@sha256:...` instead of `alpine:latest` via `TrustedImage` record).

### v2.0
- Direct OCI (`runc`) support and remove Docker dependency.

---

## How to use this file

Before starting any item above, read the relevant section in `handoff.md` first. The goal is to answer:

> Was this an actual bug, or did I already prove it wasn't?

If the answer is in the handoff or logs, do not reopen the investigation — implement the deferred fix directly.

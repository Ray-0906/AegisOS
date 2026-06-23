# AegisOS v1.0 RC1 Release Notes

**Branch:** `v1.0-dev`  
**Validation:** 84 tests run, 0 failures, 0 errors, 1 skipped (`LogCompactionTest`)  
**Log:** `suite_shared_jvm_post_fix.log`

---

## Runtime

- Fixed terminal-state ordering race (state committed before log upload; `TerminalStateOrderingTest`)
- Added finite retry limits (`JobSupervisor` max-retry ceiling → terminal `FAILED`)
- Worker execution keyed by `jobId#executionId` to prevent superseded execution interference
- Cancellation remains terminal (`CANCELLED` not overwritten by worker exit → `FAILED`)

## Storage

- Stabilized repair failover validation (`RepairLeaderFailoverTest` hardened for background audit races)
- Checkpoint monotonicity fencing on same-execution updates (`JobRegistry`)

## Scheduler

- In-flight assignment load tracking fixes hotspot placement (`HotArtifactSpreadTest`)
- Locality-aware scheduling validated (placement + metrics when observed from correct authority)

## Consensus

- Raft `stepDown` resets `votedFor` on term increase
- Cluster join harness re-resolves current leader (fixes stale-leader join flake)
- Client command forwarding validates leader id length before parse

## Testing

- Fixed JVM hygiene contamination (`TestJvmHygiene`, `JvmHygieneExtension`, `NetworkLayer` filter cleanup)
- Corrected locality validation semantics (`LocalityMetricsValidationTest` — observation point aligned with post-migration authority, not pre-partition leader reference)
- `ClusterHarness.close()` thread diagnostics

## Known deferred items

See [`docs/post-v1-roadmap.md`](docs/post-v1-roadmap.md):

- Lease configuration cleanup (`JobSupervisor.LEASE_DURATION_MS` static final)
- Worker lifecycle hardening (intermittent EOF / deserialization — green in RC1 suite)
- Executor shutdown audit

## v1.0 scope statement

AegisOS v1.0 RC1 is a **distributed Java runtime** that can:

| Capability | Status |
|------------|--------|
| Form a cluster | ✅ |
| Elect leaders (Raft) | ✅ |
| Survive node failures / partitions | ✅ |
| Run distributed jobs | ✅ |
| Checkpoint jobs | ✅ |
| Recover jobs after failover | ✅ |
| Repair under-replicated storage | ✅ |
| Install Raft snapshots | ✅ |
| Raft log compaction (production feature) | ⏳ design exists; test skipped |

Not in v1.0: container isolation, runtime artifact uploads, log compaction implementation, artifact GC.

## Key lesson from stabilization

The final stability work did not primarily involve making tests less strict; it involved correcting tests whose observation points no longer matched the distributed system's actual authority and ownership model after failover or migration.

## Further reading

- Engineering handoff: [`handoff.md`](handoff.md)
- Post-v1.0 deferred work: [`docs/post-v1-roadmap.md`](docs/post-v1-roadmap.md)
- Usage guide: [`docs/USAGE.md`](docs/USAGE.md)
- Architecture: [`docs/ARCHITECTURE_v0.95.md`](docs/ARCHITECTURE_v0.95.md)

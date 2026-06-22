# Known Limitations — AegisOS v1.0 RC1

This document captures known limitations and architectural gaps in the current release.
These are intentional design boundaries or deferred work, not undiagnosed bugs.

Deferred engineering items with investigation evidence: [`docs/post-v1-roadmap.md`](docs/post-v1-roadmap.md)

---

## Consensus & Storage

### Raft log compaction not implemented

Single-round snapshot install and recovery work. Continuous multi-round log trimming
is not integrated (`LogCompactionTest` is disabled).

**Impact:** Raft logs grow until manual intervention or node restart with snapshot load.
**Planned:** v1.1 — see `docs/LOG_COMPACTION_DESIGN.md`.

### No artifact garbage collection

Uploaded artifacts persist in AegisFS indefinitely.

**Impact:** Disk usage grows as new artifacts are uploaded.
**Planned:** Future — reference-counted GC.

### No artifact quotas

No per-user, per-namespace, or cluster-wide upload limits.

**Impact:** A single client can exhaust cluster storage.
**Planned:** Future — alongside runtime artifact API.

### No artifact versioning

Artifacts are content-addressed (SHA-256) only. No named versions or tags.

**Impact:** Users track hashes externally.
**Planned:** Future — artifact namespace and version registry.

### Cache is per-node

Each node maintains its own artifact cache. No shared distributed cache layer.

**Impact:** Without locality-aware scheduling, jobs may download artifacts remotely even
when another node has a cached copy. **Mitigated:** locality-aware scheduling is
implemented and routes jobs toward nodes with local checkpoint/artifact chunks.

---

## Runtime

### No runtime artifact uploads

Jobs cannot upload artifacts during execution. `JobContext` provides `checkpoint()`
but not `uploadArtifact()` / `downloadArtifact()`.

**Impact:** Large outputs must fit in the job return value (stored in Raft log).
**Planned:** v1.1+ — Runtime Artifact API.

### No container isolation

Jobs execute as forked JVM processes. No cgroup, namespace, or OCI sandboxing.

**Impact:** Limited isolation; resource enforcement is JVM-level only.
**Planned:** See `docs/V1_RUNTIME_DESIGN.md`.

### No resource limits enforcement

`ResourceRequest` (cpu_cores, memory_mb) affects scheduling placement only.
The runtime does not enforce limits on the executing process.

**Impact:** A job can starve co-located jobs on the same node.
**Planned:** Container runtime with cgroup enforcement.

### Worker lifecycle hardening ongoing

Intermittent worker EOF / deserialization errors were observed in fork-isolated test
runs. The v1.0 RC1 shared-JVM suite is green; worker termination protocol, socket
shutdown ordering, and `CANCEL_JOB` timing deserve further investigation.

**Evidence:** `suite_fork_isolated.log`, `docs/post-v1-roadmap.md` §2.

### Lease configuration testability

`JobSupervisor.LEASE_DURATION_MS` is a `static final` loaded at class init.
Test property overrides do not apply after first class load in a shared JVM.

**Impact:** Test ordering can affect lease-timeout behavior in integration tests.
**Planned:** v1.1 — runtime-readable lease configuration.

---

## Observability

### Limited metrics integration

Each node exposes an HTTP `/metrics` endpoint, but there is no bundled Prometheus
scrape config, Grafana dashboards, or alerting.

**Planned:** Future — operational tooling.

### No web UI

All interaction is via CLI or programmatic API.

**Planned:** Future.

---

## Operational

### No rolling upgrades

No mechanism for upgrading node software without coordinated restart.
Protocol version is not negotiated at runtime.

**Impact:** Upgrades require downtime.
**Planned:** Future.

### No authentication / authorization

All cluster members are trusted equally. No RBAC or multi-tenancy.

**Impact:** Any client that can reach a node can submit jobs and read data.
**Planned:** Future — identity and RBAC layer.

### Windows symlink fallback

Artifact mounting falls back from symlinks to file copies on Windows.

**Impact:** Higher disk use on Windows dev machines. Production targets Linux.
**Status:** Acceptable; not planned for fix.

### Long-duration chaos validation

Short and medium integration tests cover failover, partitions, and repair under load.
Extended multi-hour production soak patterns are not part of the v1.0 RC1 validation
baseline.

**Impact:** Rare timing-dependent worker or shutdown races may surface only under
sustained load. Post-v1.0 investigation recommended if reproduced consistently.

---

## What v1.0 RC1 does include

| Capability | Status |
|------------|--------|
| Cluster formation (gossip + Raft) | ✅ |
| Leader election and failover | ✅ |
| Node failure / partition tolerance | ✅ |
| Distributed job execution | ✅ |
| Job checkpointing | ✅ |
| Job recovery after failover | ✅ |
| Locality-aware scheduling | ✅ |
| Storage repair (audit + chunk repair) | ✅ |
| Raft snapshot install / recovery | ✅ |
| Raft log compaction (continuous) | ❌ deferred |

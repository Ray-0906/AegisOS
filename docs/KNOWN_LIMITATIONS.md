# Known Limitations — AegisOS v0.9

This document captures the known limitations and architectural gaps in the current release. These are intentional design boundaries, not bugs. Each entry includes the impact and the planned resolution timeline.

---

## Scheduling

### No locality-aware scheduling

The scheduler scores nodes based on CPU, memory, and current load. It does **not** consider whether a node already holds a cached copy of the required artifact or the latest checkpoint.

**Impact:** Every job recovery or failover requires a full artifact download from AegisFS, even if another node already has the artifact cached locally. This increases network traffic and recovery latency proportionally to artifact size.

**Planned fix:** Sprint 9.5 — Node scoring will incorporate `checkpointLocality` and `artifactLocality` weights.

---

## Runtime

### No runtime artifact uploads

Jobs cannot upload artifacts during execution. The `JobContext` API provides `checkpoint()` but not `uploadArtifact()` or `downloadArtifact()`.

**Impact:** Jobs that produce output files (models, reports, datasets) must encode results into the return value (`byte[]`), which is stored in the Raft log. This is unsuitable for large outputs.

**Planned fix:** Sprint 10 — Runtime Artifact API with quota enforcement.

### No container isolation

Jobs execute as forked JVM processes on the host. There is no cgroup, namespace, or Docker-based sandboxing.

**Impact:**
- Jobs can access the host filesystem (mitigated by workspace provisioning)
- No CPU/memory enforcement beyond JVM `-Xmx` flags
- No network isolation between jobs

**Planned fix:** Sprint 10 or 11 — Container execution backend.

### No resource limits enforcement

`ResourceRequest` (cpu_cores, memory_mb) is used for scheduling placement decisions only. The runtime does not enforce these limits on the executing process.

**Impact:** A job requesting 2 CPUs and 512 MB could consume all available resources on the node, starving co-located jobs.

**Planned fix:** Container runtime will enforce cgroup limits.

---

## Storage

### No artifact garbage collection

Uploaded artifacts persist in AegisFS indefinitely. There is no automatic cleanup of artifacts that are no longer referenced by any job.

**Impact:** Disk usage grows monotonically as new artifact versions are uploaded. Operators must manually track and delete stale artifacts.

**Planned fix:** Future — Reference-counted GC with orphan detection.

### No artifact quotas

Any node can upload unlimited artifacts of any size. There are no per-user, per-namespace, or cluster-wide quotas.

**Impact:** A single user can exhaust cluster storage.

**Planned fix:** To be designed alongside Runtime Artifact API.

### No artifact versioning

Artifacts are content-addressed only (SHA-256). There is no concept of named versions (`v1.2.3`), tags, or semantic version resolution.

**Impact:** Users must track SHA-256 hashes externally. Re-uploading the same content is a no-op (same hash), but there is no way to say "use the latest version of artifact X."

**Planned fix:** Future — Artifact namespace and version registry.

### Cache is per-node

Each node maintains its own independent artifact cache. There is no shared or distributed cache layer.

**Impact:** An artifact uploaded on node A and requested by a job on node B must be downloaded from AegisFS, even if node C already has it cached. With locality-aware scheduling this becomes less of an issue.

**Planned fix:** Locality-aware scheduling will naturally route jobs to nodes that already have artifacts cached.

---

## Observability

### No metrics export

The system exposes a `/metrics` HTTP endpoint per node, but there is no Prometheus scrape integration, no Grafana dashboards, and no alerting.

**Impact:** Operators have limited visibility into cluster health, job throughput, artifact cache hit rates, and checkpoint frequency.

**Planned fix:** Future — Prometheus metrics exporter.

### No web UI

All interaction is via CLI or programmatic API. There is no browser-based dashboard for job monitoring, artifact management, or cluster visualization.

**Planned fix:** Future.

---

## Operational

### No rolling upgrades

There is no mechanism for upgrading node software without full cluster restart. The Raft protocol version and protobuf schema are not negotiated at runtime.

**Impact:** Upgrading requires coordinated downtime.

**Planned fix:** Future — Protocol version negotiation.

### No authentication / authorization

All nodes in the cluster are trusted equally. There is no user identity, role-based access control, or multi-tenancy.

**Impact:** Any client that can connect to a cluster node can submit jobs, upload artifacts, and read all data.

**Planned fix:** Future — Identity and RBAC layer.

### Windows symlink fallback

On Windows, artifact mounting falls back from symlinks to file copies because symlink creation requires elevated privileges.

**Impact:** Slightly higher disk usage and slower workspace provisioning on Windows development machines. Production targets Linux where symlinks work without elevation.

**Status:** Acceptable. Not planned for fix.

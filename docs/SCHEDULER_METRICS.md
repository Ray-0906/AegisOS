# Scheduler Metrics — Baseline for Sprint 9.5

This document defines the metrics that must be collected **before and after** Sprint 9.5 (Intelligent Scheduling) to measure whether locality-aware placement actually improves system behavior.

---

## Why This Document Exists

Workflow C (ArtifactCacheReuseTest) showed that the current scheduler places two consecutive jobs using the same artifact on **different nodes**, resulting in two CACHE MISSes instead of one CACHE MISS + one CACHE HIT.

Without metrics, we can't answer:

```text
Did Sprint 9.5 actually help?
```

---

## Artifact Cache Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `artifact_cache_hits` | Counter | Number of times `ArtifactCache.resolve()` found a valid local copy |
| `artifact_cache_misses` | Counter | Number of times `ArtifactCache.resolve()` had to download from AegisFS |
| `artifact_cache_hit_ratio` | Gauge | `hits / (hits + misses)` over a sliding window |
| `artifact_cache_evictions` | Counter | Number of LRU evictions |
| `artifact_cache_pin_failures` | Counter | Eviction blocked by pinned artifacts (cache full) |
| `artifact_download_bytes` | Counter | Total bytes downloaded from AegisFS for cache misses |
| `artifact_download_duration_ms` | Histogram | Time to download artifact from AegisFS per cache miss |

### Baseline (v0.9, no locality)

From Workflow C observation:

```text
artifact_cache_hits:   0
artifact_cache_misses: 2
artifact_cache_hit_ratio: 0.0
```

**Target after Sprint 9.5:** When two jobs use the same artifact, the second job should show a CACHE HIT if any node in the cluster already has it cached.

---

## Checkpoint Recovery Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `checkpoint_local_recoveries` | Counter | Job resumed on same node that created the checkpoint |
| `checkpoint_remote_recoveries` | Counter | Job resumed on a different node (checkpoint downloaded) |
| `checkpoint_download_bytes` | Counter | Total bytes downloaded for remote checkpoint recovery |
| `checkpoint_download_duration_ms` | Histogram | Time to download checkpoint from AegisFS |
| `checkpoint_restore_duration_ms` | Histogram | Time from RESTORING → RUNNING |

### Baseline (v0.9, no locality)

All recoveries are remote. The scheduler has no knowledge of checkpoint location.

```text
checkpoint_local_recoveries:  0
checkpoint_remote_recoveries: all
```

**Target after Sprint 9.5:** Majority of recoveries should be local when the original node is still alive.

---

## Scheduler Decision Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `scheduler_placements_total` | Counter | Total number of job placement decisions |
| `scheduler_placement_duration_ms` | Histogram | Time to select a node for a job |
| `scheduler_probe_count` | Counter | Number of probe requests sent per placement decision |
| `scheduler_probe_timeout_count` | Counter | Probes that timed out |
| `scheduler_score_artifact_locality` | Histogram | Artifact locality component of winning node's score |
| `scheduler_score_checkpoint_locality` | Histogram | Checkpoint locality component of winning node's score |
| `scheduler_score_resource` | Histogram | Resource availability component of winning node's score |
| `scheduler_locality_wins` | Counter | Placements where locality score was the deciding factor |
| `scheduler_locality_overrides` | Counter | Placements where load balancing overrode locality preference |

### Baseline (v0.9, no locality)

```text
scheduler_score_artifact_locality: 0.0 (not computed)
scheduler_score_checkpoint_locality: 0.0 (not computed)
scheduler_locality_wins: 0
scheduler_locality_overrides: 0
```

---

## Job Execution Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `job_startup_duration_ms` | Histogram | Time from QUEUED → RUNNING (includes artifact resolution) |
| `job_recovery_duration_ms` | Histogram | Time from LOST → RUNNING (includes checkpoint download) |
| `job_artifact_mount_duration_ms` | Histogram | Time to mount all artifacts into workspace |

### Baseline (v0.9)

Not currently measured. These should be added as part of Sprint 9.5 implementation.

**Target:** Jobs placed on nodes with cached artifacts should show measurably lower `job_startup_duration_ms`.

---

## Network Traffic Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `aegisfs_read_bytes` | Counter | Total bytes read from AegisFS (artifact + checkpoint downloads) |
| `aegisfs_read_count` | Counter | Number of AegisFS read operations |
| `aegisfs_read_duration_ms` | Histogram | Per-read latency |

### Baseline (v0.9)

Every artifact resolution and every checkpoint recovery reads from AegisFS over the network.

**Target after Sprint 9.5:** Significant reduction in `aegisfs_read_bytes` for artifact-heavy workloads.

---

## How to Measure

### Before Sprint 9.5

Run the existing test suite and count log occurrences:

```powershell
# Artifact cache behavior
Select-String "CACHE HIT" test.log | Measure-Object
Select-String "CACHE MISS" test.log | Measure-Object

# Checkpoint recovery type  
Select-String "Restored checkpoint.*from AegisFS" test.log | Measure-Object
Select-String "Restored checkpoint.*from local" test.log | Measure-Object
```

### After Sprint 9.5

The same commands should show:
- Higher CACHE HIT ratio
- More local checkpoint recoveries
- Lower average job startup time for repeat artifacts

---

## Success Criteria

Sprint 9.5 is successful if:

1. **Cache hit ratio > 0.5** for workloads with repeated artifacts across consecutive jobs
2. **Checkpoint local recovery > 0** when original executor is still alive
3. **No hotspot degradation** — locality preference doesn't cause one node to run all jobs
4. **No regression** — full test suite still passes (79 tests, 0 failures)

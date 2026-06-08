# ADR-019: Repair Execution Contract

## Status
IMPLEMENTED — Sprint 5 complete, all acceptance tests passing, SelfHealingReaper deleted

## Date
2026-06-08

## Context
Sprint 4 established a non-mutating Verification + Recommendation pipeline:

```text
Audit → Verification → RepairRecommendation
```

Sprint 5 adds the dangerous part:

```text
RepairRecommendation → Raft Proposal → Commit → Physical Repair → Raft Confirmation → Metadata Update
```

This is the first sprint that can **move data and mutate cluster state**.
Mistakes in repair execution are expensive:
- A false-positive repair copies data to the wrong place.
- A stale recommendation triggers repair after healing has already occurred.
- A double repair duplicates data beyond the replication factor.

This ADR defines the complete contract before any repair code is written.

### Key Design Principle: Two-Phase Repair

Repair is split into two committed Raft entries:

```text
Phase A: REPAIR_CHUNK   → creates RepairTask(PENDING)   → no metadata mutation
Phase B: REPAIR_COMPLETE → applies ADD_REPLICA to FileIndex → metadata matches reality
```

This guarantees that **metadata always equals reality at every committed state
transition**. If the physical copy fails between phases, metadata is unchanged
and the audit pipeline re-detects the divergence naturally.

---

## Q1: Who Can Propose Repairs?

**Only the consensus leader.**

This follows directly from Sprint 4's leader-only audit semantics.
The leader is the only node that:
1. Runs audit scans (`StorageAuditScheduler`)
2. Produces verified recommendations (`StorageVerifier`)
3. Has a valid `AuditReportStore` (leader-local ephemeral state)

A follower NEVER proposes a repair. If leadership transfers, the new leader
must build fresh audit history from scratch before any repair can be proposed.

---

## Q2: What is the Repair Command?

Two new `CommandType` values:

### Phase A: `REPAIR_CHUNK` (value 14)

```protobuf
REPAIR_CHUNK = 14;

message RepairChunk {
  string repair_id         = 1;   // UUID, unique per repair attempt
  bytes  chunk_id          = 2;   // which chunk needs repair
  repeated int64 evidence_scans = 3;  // scan IDs from verification
  int64  verified_at       = 4;   // timestamp of verification
}
```

### Phase B: `REPAIR_COMPLETE` (value 15)

```protobuf
REPAIR_COMPLETE = 15;

message RepairComplete {
  string repair_id      = 1;   // matches the REPAIR_CHUNK repair_id
  bytes  file_id        = 2;   // resolved at copy time
  bytes  chunk_id       = 3;
  bytes  target_node_id = 4;   // where the chunk was actually stored
  bytes  source_node_id = 5;   // where the chunk was fetched from (audit trail)
}
```

### Why No Source/Target in `REPAIR_CHUNK`?

Source and target are **operational choices**, not cluster state.

Consider:

```text
t0: A has chunk, B missing → recommendation generated, source=A, target=B
t1: A crashes, C still has chunk
```

If the Raft log contains `REPAIR_CHUNK(source=A, target=B)`, that entry is
stale before commit. Operational decisions must stay **outside the log**.

`REPAIR_CHUNK` records only the **fact** that a repair is authorized.
The leader resolves source/target at execution time, after commit.

### Why Not Reuse `ADD_REPLICA`?

`ADD_REPLICA` already exists and is used during normal file write operations.
Repair is semantically distinct:
- It carries evidence metadata (`repair_id`, `evidence_scans`).
- It creates a trackable `RepairTask` in cluster state.
- It must be auditable separately from normal writes.
- It enables the two-phase pattern.

---

## Q3: What Evidence is Required Before Proposing?

The leader MUST hold a `RepairRecommendation` that satisfies:

### 3a. Verification Status
The recommendation must be derived from a `VerificationResult` with
status `VERIFIED`. No other status triggers proposal.

### 3b. Freshness Guard (Stale Recommendation Protection)
The recommendation's `recommendedAt` timestamp must be within a
configurable staleness window:

```java
// Default: 180 seconds (3 audit cycles)
// Configurable via: repairRecommendationMaxAgeSeconds
boolean isFresh(RepairRecommendation rec) {
    return (System.currentTimeMillis() - rec.recommendedAt()) < maxAgeMs;
}
```

If the recommendation is stale, it is **silently dropped**, not proposed.
Rationale: the cluster state may have changed since the recommendation
was produced. A fresh audit cycle will re-detect if the divergence
persists.

### 3c. Pre-Proposal Re-Verification
Immediately before proposing to Raft, the leader MUST:
1. Re-observe the chunk's physical state.
2. Confirm the divergence still exists.
3. Confirm all relevant nodes are still `ALIVE`.

If any of these fail, the proposal is **aborted**. This protects
against the race where a chunk self-heals between the last audit scan
and the proposal moment.

### 3d. One Repair Per Chunk In-Flight
Only one `REPAIR_CHUNK` command may be in-flight (uncommitted in the
Raft log, or in PENDING state) for a given `chunk_id` at any time.
This prevents double repairs from concurrent audit cycles.

---

## Q4: Two-Phase State Machine Flow

### Phase A: `REPAIR_CHUNK` Committed

When a `REPAIR_CHUNK` entry is committed and applied by the state machine:

1. Create a `RepairTask` in `RepairTaskStore`:
   ```java
   RepairTask {
       repairId,        // from the command
       chunkId,         // from the command
       evidenceScans,   // from the command
       verifiedAt,      // from the command
       committedAt,     // System.currentTimeMillis()
       status = PENDING
   }
   ```

2. **No FileIndex mutation.** No replica is added. No metadata changes.

3. On the leader only, schedule the physical copy as a post-commit
   side effect (see Q5).

### Between Phases: Physical Copy

The leader (and only the leader) executes the physical copy:

1. Look up the chunk in `FileIndex` to find current replica holders.
2. Select a source node that is `ALIVE` and physically holds the chunk.
3. Select a target node that is `ALIVE`, in `ClusterConfiguration.voters()`,
   and does NOT already hold the chunk.
4. Fetch the encrypted chunk from the source.
5. Store it on the target via `ChunkReplicator.storeOn()`.

If the copy **succeeds**: propose `REPAIR_COMPLETE`.
If the copy **fails**: do nothing. The `RepairTask` stays `PENDING`.
The next audit cycle will re-detect the divergence and re-enter the pipeline.

### Phase B: `REPAIR_COMPLETE` Committed

When a `REPAIR_COMPLETE` entry is committed and applied:

1. **Task existence guard:** Check that a `RepairTask` exists for the given
   `repair_id`, and its status is `PENDING`. If no matching PENDING task
   exists, the command is a **no-op**. This provides idempotency and
   protects against duplicate commits or orphaned completions.

   ```java
   Optional<RepairTask> task = taskStore.pendingByRepairId(repairId);
   if (task.isEmpty()) {
       log.info("REPAIR_COMPLETE at {} ignored: no PENDING task for {}", index, repairId);
       return;
   }
   ```

2. **Idempotency guard:** Check if the chunk already has `RF` replicas.
   If so, mark the repair task as `COMPLETE` but skip the metadata mutation.

3. **Metadata mutation:** Apply `FileIndex.applyAddReplica()` using
   the `file_id`, `chunk_id`, and `target_node_id` from the command.

4. **Update RepairTask:** Set `status = COMPLETE`.

Now and only now, metadata reflects the new replica.

### Key Invariant

```text
At every committed state transition:
    FileIndex metadata == physical reality
```

If the physical copy fails, `REPAIR_COMPLETE` is never proposed,
and `FileIndex` is never mutated. The chunk remains under-replicated
in both metadata and reality. The audit pipeline detects this naturally.

---

## Q5: Source and Target Node Selection

These are **operational decisions** made by the leader **after** `REPAIR_CHUNK`
is committed and **before** `REPAIR_COMPLETE` is proposed.

### Target Selection

```java
Optional<NodeId> selectRepairTarget(String chunkId, Set<NodeId> currentReplicaNodes) {
    // Must be in ClusterConfiguration.voters()
    // Must be PeerStatus.ALIVE
    // Must NOT already hold a replica
    // If none available: repair deferred (task stays PENDING)
}
```

### Source Selection

```java
Optional<NodeId> selectSource(String chunkId, Set<NodeId> currentReplicaNodes) {
    // Must be PeerStatus.ALIVE
    // Must physically hold the chunk
    // If none available: repair deferred (task stays PENDING)
}
```

---

## Q6: RepairTask Lifecycle

```text
PENDING  →  COMPLETE     (normal: physical copy succeeded, REPAIR_COMPLETE committed)
PENDING  →  EXPIRED      (leader cleans up tasks older than repairTaskTimeoutSeconds)
PENDING  →  PENDING      (copy failed, stays pending, next cycle re-evaluates)
```

### Stale Task Cleanup (Exact Rule)

A `RepairTask` in `PENDING` state is expired when:

```java
if (System.currentTimeMillis() - task.committedAt > repairTaskTimeoutMs) {
    task.status = EXPIRED;
}
```

Default timeout: `repairTaskTimeoutSeconds = 300` (5 minutes, i.e. 5 audit cycles).

Expiration is evaluated by the leader during each audit cycle. When a task
is expired:
- It stops blocking new `REPAIR_CHUNK` proposals for the same chunk.
- The chunk's divergence is re-detected by the audit pipeline naturally.
- A new repair cycle begins from scratch with fresh evidence.

This prevents abandoned repairs from permanently blocking new repair
attempts after leader crashes, network partitions, or other failures.

---

## Q7: Failure Mode Handling

### 7a. Source Replica Lost During Copy
- Physical copy fails (source unreachable or data missing).
- `REPAIR_COMPLETE` is never proposed.
- **FileIndex is unchanged.** Metadata still shows RF=2.
- **Recovery:** Next audit cycle detects the divergence. A new
  `REPAIR_CHUNK` is proposed with fresh evidence. Leader selects
  a different source.

### 7b. Double Repair (Race)
- Two audit cycles produce recommendations for the same chunk.
- **Defense:** `hasUncommittedRepair(chunkId)` or existing `PENDING`
  RepairTask blocks the second proposal.

### 7c. Repair After Heal (Reality Drift)
- Chunk self-heals between recommendation and commit.
- **Defense 1:** Pre-proposal re-verification catches this before
  `REPAIR_CHUNK` is proposed.
- **Defense 2:** If `REPAIR_CHUNK` commits but chunk is now healthy,
  the leader's physical copy step finds the chunk already at RF
  and skips the copy. No `REPAIR_COMPLETE` is proposed.

### 7d. Leadership Transfer Between Phase A and Phase B

This is the most important failure mode.

- **Scenario:** Leader A proposes `REPAIR_CHUNK`, task becomes `PENDING`.
  Leader A crashes before executing the physical copy. Leader B elected.

- **Rule: New leader does NOT automatically continue half-finished repairs.**

  Leader B sees the `PENDING` RepairTask in `RepairTaskStore` (it was
  replicated via Raft). But Leader B:
  - Was not the leader that produced the recommendation.
  - Has no `AuditReportStore` history (ephemeral, leader-local).
  - Has not verified the divergence itself.

  Therefore, Leader B **ignores** the PENDING task. It does NOT attempt
  the physical copy. The task stays `PENDING` until it expires.

- **Recovery:** Leader B's next audit cycle detects the divergence
  independently, builds its own evidence, and may propose a new
  `REPAIR_CHUNK` once the old task has expired.

- **Test:** `RepairLeaderFailoverTest` validates this scenario end-to-end.

### 7e. Target Node Dies After `REPAIR_COMPLETE`
- Metadata says chunk is on target, but target is DEAD.
- **Recovery:** Future audit detects the chunk as under-replicated.
  ADR-017 says dead-node under-replication is `NODE_UNAVAILABLE` →
  requires manual membership reconfiguration.

### 7f. Insufficient Healthy Replicas
- All source nodes are DEAD. No chunk data available.
- **Effect:** `REPAIR_CHUNK` commits, but physical copy is impossible.
  Task stays `PENDING` until expired.
- **Operator Action:** Manual intervention required (data recovery).

### 7g. Physical Copy Fails (New: explicit test required)
- `REPAIR_CHUNK` committed. Leader attempts copy. Copy fails.
- `REPAIR_COMPLETE` is NOT proposed.
- **FileIndex unchanged.** Metadata still shows under-replicated.
- Recommendation persists (or re-appears next cycle).
- Next audit cycle retries.
- **Test:** `RepairCopyFailureTest` validates this scenario.

---

## Q8: Legacy Code Removal

**`SelfHealingReaper.java` MUST be deleted.**

It violates ADR-016, ADR-017, and ADR-019:
- Directly proposes `ADD_REPLICA` without verification pipeline.
- Uses Gossip liveness directly for placement decisions.
- Does not require two consecutive audit scans.
- Does not go through the two-phase repair flow.

There must be exactly one repair path:

```text
Audit → Verify → Recommend → REPAIR_CHUNK → Copy → REPAIR_COMPLETE
```

### Implementation Order

1. Build Sprint 5 repair pipeline (SelfHealingReaper remains temporarily).
2. Pass all acceptance tests (`RepairExecutionSignOffTest`, `RepairCopyFailureTest`,
   `RepairLeaderFailoverTest`).
3. **Then** delete `SelfHealingReaper` and update all references.

Rationale: if Sprint 5 stalls halfway, the old repair mechanism still
functions. The final release commit removes it.

---

## Invariants (Machine-Readable Reference)

```text
REPAIR_PROPOSER           = LEADER_ONLY
REPAIR_PHASE_A            = REPAIR_CHUNK (creates PENDING RepairTask, no metadata mutation)
REPAIR_PHASE_B            = REPAIR_COMPLETE (validates PENDING task, applies ADD_REPLICA, task → COMPLETE)
OPERATIONAL_DATA_IN_LOG   = PHASE_B_ONLY (source/target in REPAIR_COMPLETE, not REPAIR_CHUNK)
EVIDENCE_REQUIRED         = VERIFIED_RECOMMENDATION + FRESHNESS_GUARD + PRE_PROPOSAL_OBSERVATION
STALENESS_CONFIG          = repairRecommendationMaxAgeSeconds (default: 180)
TASK_EXPIRATION           = repairTaskTimeoutSeconds (default: 300)
TASK_EXPIRATION_RULE      = committedAt + timeout < now => EXPIRED
IN_FLIGHT_LIMIT           = 1 per chunk_id (PENDING task blocks new proposals)
METADATA_INVARIANT        = FileIndex == reality at every committed transition
COMPLETE_GUARD            = PENDING task must exist + repairId must match
COPY_FAILURE_RECOVERY     = NEXT_AUDIT_CYCLE (REPAIR_COMPLETE never proposed, FileIndex unchanged)
LEADER_FAILOVER           = New leader does NOT continue half-finished repairs
LEGACY_REMOVAL            = SelfHealingReaper DELETED (after acceptance tests pass)
```

## Consequences
- `REPAIR_CHUNK` and `REPAIR_COMPLETE` must be added to `CommandType` enum in `aegis.proto`.
- `RepairChunk` and `RepairComplete` protobuf messages must be defined.
- `RepairTaskStore` must be built as a new state machine component for tracking PENDING/COMPLETE tasks.
- `ClusterStateMachine` needs applier registrations for both command types.
- `SelfHealingReaper.java` must be deleted. All references removed.
- `StorageAuditScheduler` (or a new `RepairProposer`) must read recommendations
  and drive the proposal flow.
- Physical chunk copy is strictly **between** phases, never at commit time.
- `repairRecommendationMaxAgeSeconds` must be a configurable parameter.
- Tests referencing `SelfHealingReaper` must be updated to use the new repair pipeline.

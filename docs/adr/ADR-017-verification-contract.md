# ADR-017: Verification Contract

## Status
ACCEPTED (Locked for Sprint 3)

## Date
2026-06-07

## Context
The reconciliation engine (Sprint 3) requires a precise specification of
what constitutes sufficient evidence to propose a metadata repair via Raft.
Without this contract, repair logic will make implicit assumptions that
differ between implementors and may produce false-positive repairs committed
with full consensus guarantees.

A false-positive repair is worse than metadata drift.
It is consistent wrongness, replicated cluster-wide.

## Q1: Who Initiates Verification?
The **LEADER NODE exclusively**.

Two triggers (both required):
- **PERIODIC**: every `aegis.audit.interval.seconds` (default: 60)
- **MEMBERSHIP_CHANGE**: fires immediately on node JOIN, node LEAVE, or node
  declared DEAD. No delay. These events are rare and high-signal.

Non-leader nodes NEVER initiate verification.
They respond to leader queries only.

## Q2: What Evidence is Required? (By Object Type)

Evidence requirements are OBJECT-SPECIFIC. There is no universal rule.

### Storage Chunks

A repair proposal MAY be submitted to Raft ONLY when ALL of the
following conditions are simultaneously true:

1. **Physical Observation Agreement**: The chunk is confirmed absent from a node that Raft metadata declares as a replica holder.
2. **Membership Validation**: That node is confirmed LIVE in current Raft membership. (Absence on a DEAD or UNREACHABLE node is expected and is NOT a repair trigger.)
3. **Checksum Match**: The chunk's checksum on surviving holders matches the registry checksum. (Protects against corrupted-registry false positives.)
4. **Two Consecutive Audit Scans**: The exact same divergence was observed in at least TWO consecutive audit scans, each separated by the full audit interval. (Single-scan divergence NEVER triggers a proposal.)
5. The surviving confirmed replica count is below the configured replication factor.

*In short: Two consecutive audit scans + membership validation + physical observation agreement.*

### Jobs

A repair proposal MAY be submitted to Raft ONLY when ALL of the
following conditions are simultaneously true:

1. [HARD PRECONDITION] The node assigned to the job is declared DEAD
   in Raft membership. Job repair NEVER triggers on a live node.
   Job absence on a live node is NOT sufficient evidence — it may be
   a GC pause, slow executor, or transient condition.
2. The heartbeat from the assigned node has expired past the configured
   threshold (`aegis.job.heartbeat.timeout.seconds`, default: 30).
3. The job is absent from execution records on the assigned node.
4. The same orphaned state was observed in at least TWO consecutive
   audit scans.

### Artifacts

A repair proposal MAY be submitted to Raft ONLY when ALL of the
following conditions are simultaneously true:

1. The artifact is declared present in Raft registry metadata.
2. The SHA-256 checksum of the artifact on its cache node does not
   match the registry checksum, OR the artifact is completely absent
   from a declared cache node.
3. The cache node is confirmed LIVE.
4. The same divergence was observed in at least TWO consecutive scans.

## Q3: What Action is Proposed?

Important workflow constraint. The system MUST follow this sequence strictly:

```text
Audit
   ↓
Verification
   ↓
Repair Recommendation
   ↓
Raft Proposal
   ↓
Commit
   ↓
Repair Execution
```

**NOT:** `Audit → Repair`

We do NOT bypass consensus. The output of the Verification phase is a `Repair Recommendation` that gets submitted to the `ClusterStateMachine` via a Raft `StateCommand` (e.g. `ADD_REPLICA`, `SCHEDULE_JOB`). Only upon successful Raft commit does the actual repair execution (e.g. copying the chunk, spinning up the job) take place.

## Minimum Confirmation Threshold
Single-scan divergence NEVER produces a repair proposal.
The two-scan minimum is enforced STRUCTURALLY (e.g. by an `AuditReportStore`
consecutive-scan API), not by convention or comment.

## False-Positive Risk
This is the primary risk of the reconciliation engine.
The two-scan rule and the object-type-specific preconditions are the
primary defenses. Do not weaken or bypass them without a new ADR.

# ADR-016: Authoritative Source of Truth Policy

## Status
ACCEPTED

## Date
2026-06-07

## Context
AegisOS maintains metadata about chunks, jobs, artifacts, and node membership
across multiple subsystems. During v0.3 soak testing, mismatches between
Raft-committed metadata and observed cluster state were confirmed
(e.g. MetadataReplicas=53, LiveReplicas=3). A formal policy is required
before any reconciliation logic is implemented.

## Decision

**Rule 1 — Raft metadata is authoritative.**
It is the ground truth of what the system believes to be true.

**Rule 2 — Observed cluster state is evidence.**
It is input to a verification process. It is never directly authoritative.

**Rule 3 — Metadata is modified ONLY through a committed Raft log entry.**
No code path anywhere in the system may mutate metadata through any
other mechanism. This is a structural constraint, not a convention.

**Rule 4 — Observations never directly trigger metadata mutations.**
An observation — however consistent, however many nodes report it — triggers
verification. Verification produces a repair proposal. A committed Raft entry
executes the metadata change.

**Rule 5 — The reconciliation pipeline is the only permitted path from
observation to metadata change.**
The pipeline is: Observe → Verify → Propose → Commit → Repair.
Shortcutting any stage is an architectural violation.

## Consequences
- All existing code that modifies metadata outside Raft must be identified
  and removed.
- The reconciliation engine (Sprint 3) must enforce Rule 3 structurally.
- Any contributor implementing repair logic must read this ADR first.

## Invariant (machine-readable reference)
METADATA_AUTHORITY  = RAFT
OBSERVATION_ROLE    = EVIDENCE_ONLY
MUTATION_GATE       = RAFT_COMMIT
RECONCILE_PIPELINE  = OBSERVE → VERIFY → PROPOSE → COMMIT → REPAIR

# ADR-023: Runtime Persistence Foundation

## Status
Proposed (target: v1.3)

## Context
As AegisOS matures, the runtime layer needs durable state to survive full cluster restarts without losing execution history, lease ownership, or job assignments. Currently, all runtime state is reconstructed from the Raft log on restart, which works but loses fine-grained execution metadata.

This ADR establishes the architectural foundation for runtime persistence **without implementing actual persistence yet**. The goal is to freeze the journal boundaries and ownership model so that v1.3 implementation does not require architectural changes.

## Decision

### Journal Ownership
`ProcessRuntimeAgent` exclusively owns all runtime journals. Persistence is execution-related, not scheduling-related.

```text
ProcessRuntimeAgent
        ↓
  ┌─────┼─────────────┐
  │     │             │
ExecutionJournal  LeaseJournal  OwnershipJournal
```

`JobRegistry` and `Scheduler` must NOT write to journals directly. They continue to use Raft for authoritative state.

### Journal Separation
Three independent journals. They must never be merged.

#### ExecutionJournal
Records the lifecycle of individual job executions.

Stores:
- `executionId` — unique per attempt
- `jobId` — the parent job
- `startedAt` — execution start timestamp
- `completedAt` — execution end timestamp (nullable)
- `result` — exit code, output reference, or error

#### LeaseJournal
Records lease ownership and renewal history.

Stores:
- `leaseOwner` — the node holding the lease
- `renewals` — renewal timestamps
- `expiration` — current expiration time

#### OwnershipJournal
Records job-to-node assignment history.

Stores:
- `jobId` — the job being tracked
- `nodeId` — the assigned node
- `assignment changes` — timestamped transitions (assigned, migrated, revoked)

### Invariants
- **INV-013**: Journals are append-only. No in-place updates or deletes.
- Journals are **local** to each node. They are not replicated through Raft.
- Journals are **supplementary**. The Raft log remains the authoritative source of truth for job state. Journals provide execution-level detail that Raft intentionally does not track.
- Compaction of journals is a separate, explicit operation with its own lifecycle.

## Alternatives Considered

### Single unified journal
Rejected. A single journal mixing execution, lease, and ownership events would create tight coupling and make independent compaction impossible.

### Journal owned by JobRegistry
Rejected. `JobRegistry` is a Raft state machine projection. Mixing it with local persistence would violate the separation between consensus state and execution state.

## Consequences
- v1.2 will create the journal interfaces and data model.
- v1.3 will implement the actual persistence layer.
- `ProcessRuntimeAgent` gains additional responsibility but remains the sole mediator between Raft and physical execution.

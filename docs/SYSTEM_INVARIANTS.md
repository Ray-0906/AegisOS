# AegisOS System Invariants

These invariants are fundamental architectural rules that must never be violated. They represent the contract between AegisOS subsystems and are enforced through code review, testing, and architectural audits.

## Consensus

**INV-001: Only leaders may propose consensus changes.**
Followers and non-voters must reject client commands with `NotLeaderException`. No component may bypass this by writing directly to the Raft log.

**INV-002: RPC futures are completed only by response messages.**
A request message must never complete a pending future. Only a response message with a matching correlation ID may resolve a future. (See ADR-020.)

## Observability

**INV-003: Metrics endpoints are read-only.**
`MetricsServer` must never mutate any system state. It is a pure projection of `MetricsRegistry`, `TimelineRegistry`, and `JobRegistry`.

**INV-004: Metrics never mutate state.**
`MetricsRegistry` aggregates counters and gauges. It must never trigger side effects, state transitions, or consensus proposals.

**INV-005: TimelineRegistry is local-only and never persisted.**
Timeline data is a bounded, in-memory derived view. It must never be written to the Raft log or stored on disk. It is non-authoritative.

**INV-006: Observability data is non-authoritative.**
Data served by `/jobs`, `/topology`, and `/metrics` endpoints is eventually consistent and must never be used to drive scheduling, consensus, or state transition decisions.

**INV-007: Raft never writes to TimelineRegistry.**
Timeline events come from the workload layer (`JobRegistry`, `ProcessRuntimeAgent`, `JobSupervisor`), never from `RaftNode` or `ConsensusModule`. (See ADR-022.)

## Runtime

**INV-008: ProcessRuntimeAgent is an orchestrator, not an executor.**
It manages the worker state machine and coordinates with Raft, but must never directly spawn JVM processes or execute `docker run`. All physical execution is delegated to `RuntimeBackend` implementations. (See ADR-022.)

**INV-009: Runtime backends never know about scheduling or Raft.**
`JvmRuntimeBackend` and `DockerRuntimeBackend` must not import consensus or scheduler packages. They receive work from the orchestrator and report results back. All distributed state is mediated by `ProcessRuntimeAgent`.

**INV-010: Container IDs never escape DockerRuntimeBackend.**
Docker container IDs, image digests, and other container-specific identifiers are internal to `DockerRuntimeBackend`. `ProcessRuntimeAgent` interacts only through the `RuntimeBackend` abstraction.

**INV-011: Containers are restartable, not resumable.**
Upon failure, a container execution starts from a clean slate. There is no warm restart or CRIU snapshot support in v1.1. (See ADR-021.)

**INV-012: Execution IDs are immutable.**
Once assigned, an execution ID must never be reused or reassigned. A new execution of the same job receives a new execution ID.

## Persistence (v1.2+)

**INV-013: Journals are append-only.**
`ExecutionJournal`, `LeaseJournal`, and `OwnershipJournal` must never update or delete entries in place. All state changes are appended as new entries. Compaction is a separate, controlled operation.

## Testing

**INV-014: Tests must not rely on arbitrary sleeps.**
New tests must use `EventAwaiter`, `TestBarrier`, `ClusterAwaiter`, or `TestClock` for synchronization. `Thread.sleep()` is only permitted in benchmarks and explicit timing tests.

**INV-015: Quarantined tests are temporary, never permanent.**
Every quarantined test must have an owner and a target milestone for resolution. Quarantine is a triage mechanism, not a disposal mechanism.

**INV-016: Tests must synchronize on observable events, never on elapsed time.**
Do not use time-based sleeps. All waits must be predicated on explicit cluster state changes, replications, or log appends.

## Distributed RPC Liveness

**INV-017: Distributed RPCs must fail fast.**
Subsystems must not wait on nodes known to be dead. Dead node detection or connection closure must immediately fail pending RPC futures. (See ADR-024.)

**INV-018: Runtime worker threads must never block attempting to repair consensus failures.**
Execution threads (e.g. `ProcessRuntimeAgent`) and periodic schedulers must not wrap consensus proposals in internal retry loops (`Thread.sleep()`). They must fail fast and defer retries to the next natural execution cadence.

**INV-019: Periodic schedulers are best-effort systems.**
Missing one cycle is acceptable. Blocking a scheduler thread is forbidden. Examples of best-effort schedulers include `RepairProposer`, `ObservedStateCollector`, and `HealthMonitor`.

## Test Timing

**INV-020: Test timeouts must be derived from production timing contracts.**
Timeouts cannot be increased to eliminate flakes. Every test timeout must have a documented derivation from system-level bounds. If a timeout cannot be mathematically derived from known invariants, it is suspicious and must be investigated.

Example derivation:
```
LongRunningCheckpointChaosTest

10s lease expiration
+ 5s supervisor tick
+ 10s leader election bound
+ 10s replication bound
= 35s
→ Use 45s (1.3x headroom)
```

**INV-021: No architectural work is allowed while a reliability freeze is open.**
A reliability freeze is a hard gate. While it is open, no production code changes, no new awaiters, no new ADRs, and no broad test refactoring are permitted. Only investigation and measurement of the blocking issue is allowed. This prevents blast radius expansion from cascading fix-break-fix cycles.

**INV-022: Repair execution must not depend on gossip DEAD detection.**
Repair execution may depend on repair eligibility, lease expiration, or under-replication detection. Discovery convergence (gossip DEAD detection) is an independent concern and must not act as a precondition for subsystem execution. Tests verifying these subsystems must measure and assert them independently.

**INV-023: Tests must synchronize against the owning subsystem clock.**
Tests must synchronize against the owning subsystem clock. Raft assertions -> Raft events, Repair assertions -> Repair events, Discovery assertions -> Discovery events. Cross-subsystem synchronization is forbidden unless explicitly documented.

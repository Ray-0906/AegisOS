# Contributing to AegisOS

## Code Style

Follow the existing code conventions in the project. Use consistent formatting and meaningful variable names.

## Testing Rules

### No new `Thread.sleep()` in tests

All new test synchronization must use one of the following primitives:

| Primitive       | Use Case                                              |
| --------------- | ----------------------------------------------------- |
| `EventAwaiter`  | Waiting for a specific state transition or event       |
| `TestBarrier`   | Synchronizing node startup or multi-node coordination  |
| `ClusterAwaiter`| Waiting for cluster-level conditions (quorum, leader)  |
| `TestClock`     | Controlling time-dependent behavior (leases, timeouts) |

**Exceptions:**
- Benchmarks (`PerformanceBenchmarks`)
- Explicit timing tests where real wall-clock delay is the subject under test

**Rationale:** Arbitrary sleeps are the primary source of test flakiness in distributed systems. They create false confidence when they pass and false failures when they don't. Deterministic synchronization primitives make tests both faster and more reliable.

### Test Classification

When adding a new test, tag it for the appropriate CI profile:

| Profile         | Purpose                              | Trigger  |
| --------------- | ------------------------------------ | -------- |
| `fast`          | Unit + deterministic integration     | Every PR |
| `integration`   | All stable integration tests         | Nightly  |
| `chaos`         | Fault injection and chaos tests      | Weekly   |
| `quarantine`    | Known flaky tests under active fix   | Weekly   |

### Quarantine Policy

Quarantined tests must have:
- An entry in `docs/TEST_DEBT.md`
- An owner responsible for the fix
- A target milestone for resolution (INV-015)

Quarantine is a triage mechanism, not a disposal mechanism.

## Architecture Rules

Before adding new code, review:
- `docs/ARCHITECTURE_OWNERSHIP.md` — allowed and forbidden dependency flows
- `docs/SYSTEM_INVARIANTS.md` — fundamental rules that must never be violated

Any code that crosses an ownership boundary or violates a system invariant will be rejected during review.

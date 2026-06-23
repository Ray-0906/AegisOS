# Test Debt Ledger

This document tracks `Thread.sleep()` usages in production-quality test suites that should be replaced with deterministic synchronization primitives.

```text
TEST_DEBT SNAPSHOT (v1.2 start)

Production suites:
Thread.sleep usages: 60

P0: 2
P1: 12
P2: 28
P3: 18

Target:
Sprint 1 → <40
Sprint 2 → <20
Sprint 3 → 0 P0/P1
```

**Scope:** `aegis-test-cluster`, `aegis-runtime`, `aegis-network` integration tests only.
**Out of scope:** `aegis-cli`, demo jobs, benchmarks, experimental tests.

## Priority Classification

| Priority | Area                   | Example                                       | Status |
| -------- | ---------------------- | --------------------------------------------- | ------ |
| P0       | Flaky release blockers | `CorruptCheckpointRecoveryTest`, `Phase7Test` | Done   |
| P1       | Chaos tests            | `ChaosSoakTest`, `WorkerFailure*`             | Open   |
| P2       | Integration tests      | `LongRunningCheckpointChaosTest`, `Snapshot*` | Open   |
| P3       | Benchmarks             | `PerformanceBenchmarks`                       | Defer  |

## Replacement Primitives

| Pattern                        | Primitive       | Example Usage                                    |
| ------------------------------ | --------------- | ------------------------------------------------ |
| sleep waiting for state        | `EventAwaiter`  | `awaiter.awaitJobState(jobId, COMPLETED)`         |
| sleep waiting for lease        | `TestClock`     | `clock.advance(Duration.ofSeconds(10))`           |
| sleep waiting for node startup | `TestBarrier`   | `barrier.awaitAllNodesReady()`                    |
| sleep waiting for replication  | `EventAwaiter`  | `awaiter.awaitReplication(jobId)`                 |
| sleep waiting for quorum       | `ClusterAwaiter`| `clusterAwaiter.awaitQuorum()`                    |
| sleep waiting for leader       | `ClusterAwaiter`| `clusterAwaiter.awaitLeaderElection()`            |

## aegis-test-cluster (P0 — Flaky Release Blockers)

| File                              | Line | Sleep     | Pattern                  | Replacement     |
| --------------------------------- | ---- | --------- | ------------------------ | --------------- |
| `CorruptCheckpointRecoveryTest`   | —    | (await)   | wait for lease + requeue | `ClusterAwaiter`|
| `Phase7Test`                      | —    | (await)   | wait for routing         | `ClusterAwaiter`|

## aegis-test-cluster (P1 — Chaos Tests)

| File                              | Line | Sleep     | Pattern                    | Replacement      |
| --------------------------------- | ---- | --------- | -------------------------- | ---------------- |
| `ChaosSoakTest`                   | 170  | 8000ms    | wait between chaos cycles  | `TestClock`      |
| `OvernightSoakTest`               | 104  | 15000ms   | wait between cycles        | `TestClock`      |
| `OvernightSoakTest`               | 131  | 500ms     | wait for state poll        | `EventAwaiter`   |
| `OvernightSoakTest`               | 196  | 8000ms    | wait between kills         | `TestClock`      |
| `OvernightSoakTest`               | 269  | variable  | cycle interval             | `TestClock`      |
| `OvernightSoakTest`               | 282  | 500ms     | wait for state poll        | `EventAwaiter`   |
| `PartitionSafetyTest`             | 61   | 3000ms    | wait for partition effect  | `ClusterAwaiter` |
| `PartitionSafetyTest`             | 84   | 5000ms    | wait for heal              | `ClusterAwaiter` |
| `WorkerExitVsCancelRaceTest`      | —    | (await)   | wait for race window       | `EventAwaiter`   |
| `StaleCheckpointFenceTest`        | —    | (await)   | wait for lease expiry      | `TestClock`      |

## aegis-test-cluster (P2 — Integration Tests)

| File                                    | Line  | Sleep    | Pattern                   | Replacement      |
| --------------------------------------- | ----- | -------- | ------------------------- | ---------------- |
| `ArtifactRestartRecoveryTest`           | 42    | 2000ms   | wait for node startup     | `TestBarrier`    |
| `ArtifactRestartRecoveryTest`           | 71    | 3000ms   | wait for recovery         | `ClusterAwaiter` |
| `CheckpointLocalityTest`               | 58,90 | 100ms    | poll for checkpoint       | `EventAwaiter`   |
| `CheckpointPersistenceTest`            | 46    | 500ms    | wait for persistence      | `EventAwaiter`   |
| `CheckpointUploadCancellationTest`     | 44,51 | 100ms    | poll for checkpoint       | `EventAwaiter`   |
| `CheckpointUploadCancellationTest`     | 119   | 2000ms   | wait for cancellation     | `EventAwaiter`   |
| `ConfigurationSurvivesRestartTest`     | 85    | 50ms     | poll for config           | `ClusterAwaiter` |
| `CorruptSnapshotRecoveryTest`          | 30    | 50ms     | poll for snapshot         | `EventAwaiter`   |
| `CorruptSnapshotRecoveryTest`          | 45    | 1000ms   | wait for recovery         | `ClusterAwaiter` |
| `DuplicateExecutionPreventionTest`     | 39    | 1000ms   | wait for propagation      | `ClusterAwaiter` |
| `DuplicateExecutionPreventionTest`     | 78    | 100ms    | poll for state            | `EventAwaiter`   |
| `ExecutionRecoveryAfterSnapshotTest`   | 83    | 2000ms   | wait for recovery         | `ClusterAwaiter` |
| `InstallSnapshotAfterCompactionTest`   | 43    | 100ms    | poll for compaction       | `EventAwaiter`   |
| `InstallSnapshotSuffixPreservationTest`| 45    | 100ms    | poll for snapshot         | `EventAwaiter`   |
| `InstallSnapshotVerificationTest`      | 21    | 50ms     | poll for snapshot         | `EventAwaiter`   |
| `InstallSnapshotVerificationTest`      | 45,49 | 1000ms   | wait for IO flush         | `EventAwaiter`   |
| `JobCancellationTest`                  | 50    | 2000ms   | wait for cancellation     | `EventAwaiter`   |
| `JobLogPersistenceTest`                | 44    | 1000ms   | wait for persistence      | `EventAwaiter`   |
| `JoinModeNonElectableTest`             | 24    | 3000ms   | wait for join             | `ClusterAwaiter` |
| `LeaderFailoverJobRecoveryTest`        | 25    | 1000ms   | wait for propagation      | `ClusterAwaiter` |
| `LogTruncationVerificationTest`        | 20    | 50ms     | poll for truncation       | `EventAwaiter`   |
| `LogTruncationVerificationTest`        | 30    | 2000ms   | wait for truncation       | `EventAwaiter`   |
| `LogTruncationVerificationTest`        | 39    | 1000ms   | wait for IO settle        | `EventAwaiter`   |
| `LongRunningCheckpointChaosTest`       | 40    | 3000ms   | wait between chaos        | `TestClock`      |
| `MembershipVisibilityTest`             | 37    | 2000ms   | wait for membership       | `ClusterAwaiter` |
| `MembershipVisibilityTest`             | 67    | 500ms    | wait for membership       | `ClusterAwaiter` |
| `NonVoterGrantsVoteTest`              | 140   | 1000ms   | wait for vote             | `ClusterAwaiter` |

## aegis-test-cluster (Infrastructure)

| File                  | Line     | Sleep  | Pattern           | Replacement      |
| --------------------- | -------- | ------ | ----------------- | ---------------- |
| `ClusterHarness`      | 194      | 100ms  | poll for condition| `EventAwaiter`   |
| `ClusterHarness`      | 278      | 50ms   | poll for await    | `EventAwaiter`   |
| `ClusterHarness`      | 300      | 50ms   | poll for await    | `EventAwaiter`   |

## aegis-network (Integration Tests)

| File                            | Line  | Sleep  | Pattern             | Replacement    |
| ------------------------------- | ----- | ------ | ------------------- | -------------- |
| `RpcCorrelationIsolationTest`   | 38    | 100ms  | wait for RPC reply  | `EventAwaiter` |
| `RpcCorrelationIsolationTest`   | 84    | 100ms  | wait for RPC reply  | `EventAwaiter` |

## aegis-runtime (Production Code — Separate Review)

These are production sleeps in runtime code. They require careful review as some may be legitimate retry/backoff patterns.

| File                     | Line | Sleep      | Pattern              | Notes                      |
| ------------------------ | ---- | ---------- | -------------------- | -------------------------- |
| `JobSupervisor`          | 243  | 5000ms     | heartbeat loop       | Likely legitimate          |
| `ProcessRuntimeAgent`    | 366  | 100ms      | retry loop           | Consider backoff primitive |
| `ProcessRuntimeAgent`    | 520  | 100ms      | wait for leader      | Consider backoff primitive |
| `ProcessRuntimeAgent`    | 539  | 100ms      | wait for leader      | Consider backoff primitive |
| `ProcessRuntimeAgent`    | 629  | variable   | retry delay          | Likely legitimate          |
| `ProcessRuntimeAgent`    | 656  | variable   | checkpoint delay     | Likely legitimate          |
| `ProcessSupervisor`      | 142  | 2000ms     | reconnect delay      | Likely legitimate          |
| `WorkerMain`             | 53   | 2000ms     | reconnect delay      | Likely legitimate          |
| `WorkerMain`             | 155  | 100ms      | shutdown drain       | Likely legitimate          |

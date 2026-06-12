# AegisOS Engineering Handoff

> Current handoff for the next engineer/agent. Read this before changing code.
> It captures the problems investigated during the latest stabilization pass,
> the actual root causes found, the fixes applied, and what still deserves follow-up.

---

## 0. Current Status

- **Project:** AegisOS, a Java 21 peer-to-peer distributed runtime with secure networking, gossip membership, Raft consensus, AegisFS storage, job execution, checkpointing, repair, and locality-aware scheduling.
- **Latest full validation:** `mvn clean package`
- **Latest result:** `BUILD SUCCESS`
- **Latest test total:** `Tests run: 83, Failures: 0, Errors: 0, Skipped: 1`
- **Latest elapsed time:** `17:04 min`
- **Most recent user log:** `build.log`, finished `2026-06-12T14:42:12+05:30`

The original build hang and later `HotArtifactSpreadTest` failure are resolved.
The remaining visible exceptions in `build.log` are mostly expected chaos-test or negative-test behavior, plus a few shutdown/logging-noise candidates listed below.

---

## 1. Stabilization Story So Far

### 1.1 Original Symptom: `mvn clean package` Appeared to Hang

The first major incident looked like a distributed live-lock during the integration suite:

- Phase 5 snapshot/storage tests ran for a very long time.
- Logs showed leader churn, election storms, anti-entropy activity, snapshot load fallback, and repeated background work.
- The suite appeared to stall on “dead logs” instead of terminating.

The initial suspicion was consensus/storage starvation: Raft heartbeats and membership work were being delayed while storage repair/audit/checkpoint paths were active.

### 1.2 Shutdown and Background-Executor Races

After improving shutdown behavior, full builds started completing instead of hanging, but logs showed lifecycle races such as:

- `RejectedExecutionException`
- tasks rejected by terminated executors
- cleanup/heartbeat work submitted while nodes were already shutting down

These were not correctness failures, but they polluted logs and could leak state if a job startup failed halfway through.

Fixes applied:

- `ProcessRuntimeAgent` now skips job execution when shutting down.
- Heartbeat scheduling is guarded against `RejectedExecutionException`.
- Shutdown-time updates avoid reporting false hard failures when Raft is already stopping.
- Delayed cleanup scheduling is guarded so cleanup is skipped cleanly during shutdown.
- Transient storage/network shutdown failures are logged compactly.

Relevant files:

- `aegis-runtime/src/main/java/com/aegisos/runtime/ProcessRuntimeAgent.java`
- `aegis-network/src/main/java/com/aegisos/network/PeerConnection.java`
- `aegis-fs/src/main/java/com/aegisos/fs/ChunkReplicator.java`

### 1.3 Cancellation Semantics: `CANCELLED -> FAILED`

Logs showed suspicious state transitions where a cancelled job could later become failed:

```text
CANCELLED -> FAILED
```

Root cause:

- The runtime could kill a local worker after cancellation.
- The worker failure path then attempted to publish `FAILED`.
- `JobRegistry` previously logged invalid transitions but still applied some Raft-committed state updates.

Fixes applied:

- `JobRegistry` treats terminal states deterministically.
- Terminal states are not overwritten by later invalid terminal rewrites.
- `ProcessRuntimeAgent` preserves `CANCELLED` when the worker exits after cancellation.
- `JobCancellationTest` now expects cancellation to remain terminal.

Relevant files:

- `aegis-runtime/src/main/java/com/aegisos/runtime/JobRegistry.java`
- `aegis-runtime/src/main/java/com/aegisos/runtime/ProcessRuntimeAgent.java`
- `aegis-test-cluster/src/test/java/com/aegisos/cluster/JobCancellationTest.java`

### 1.4 Worker Recovery and Supervisor Identity

Recovery tests exposed stale/superseded execution behavior.

Root cause:

- Local process supervision was keyed too coarsely by job identity.
- A later execution could interact badly with an earlier execution if both existed around failover/recovery boundaries.

Fix applied:

- `JobExecutor` supervisors are keyed by `jobId#executionId`, separating executions.

Relevant file:

- `aegis-runtime/src/main/java/com/aegisos/runtime/JobExecutor.java`

### 1.5 Checkpoint Fencing and Monotonicity

`LeaderFailoverCheckpointTest` exposed a race where a new leader could observe a checkpoint sequence lower than the old leader had observed before failover.

Root causes:

- Checkpoint updates are asynchronous relative to job execution.
- A leader failover can expose state before every local registry view has caught up.
- Same-execution checkpoint metadata needed monotonic protection.

Fixes applied:

- `JobRegistry` now ignores stale same-execution checkpoint updates where the current checkpoint sequence is newer.
- `LeaderFailoverCheckpointTest` waits for the new leader registry to catch up instead of sampling immediately after election.

Relevant files:

- `aegis-runtime/src/main/java/com/aegisos/runtime/JobRegistry.java`
- `aegis-test-cluster/src/test/java/com/aegisos/cluster/LeaderFailoverCheckpointTest.java`

### 1.6 Cluster Join Harness Flake

A focused full-suite validation once failed in `Phase6Test` during `harness.start(5)`:

```text
New node ... did not join Gossip on leader ... within 30s
```

Root cause:

- `ClusterHarness.addNodeWithHome` captured one leader and waited for that specific leader to observe the new node.
- During full-suite load, leadership could move or a different leader could already see the node.
- The harness was checking a stale leader snapshot, not the current cluster leader view.

Fix applied:

- `ClusterHarness` now re-checks the current leader during join/catch-up/proposal flow.
- `ADD_VOTER` is proposed on a current leader that sees the joining node.

Relevant file:

- `aegis-test-cluster/src/test/java/com/aegisos/cluster/ClusterHarness.java`

### 1.7 Client Command Forwarding Noise

One validation run showed:

```text
NodeId must be 32 bytes
```

Root cause:

- A forwarded `CLIENT_COMMAND` failure response could carry a malformed or absent leader id.
- The caller attempted to parse any non-empty leader id bytes as a `NodeId`.

Fix applied:

- `ConsensusModule` validates leader id length before parsing.
- Malformed leader ids are ignored and reported as no known leader instead of causing parse noise.

Relevant file:

- `aegis-consensus/src/main/java/com/aegisos/consensus/ConsensusModule.java`

### 1.8 Final Build Failure: `HotArtifactSpreadTest`

The last real build failure was:

```text
HotArtifactSpreadTest.testHotArtifactSpillsOver:103
Hotspot protection failed: all jobs went to a single node
```

Root cause:

- The test intentionally fills the artifact-cache node with dummy reservations.
- The hot jobs must spill to non-cache nodes.
- Before the fix, the scheduler only saw already-running jobs and local resource reservations.
- Burst-submitted jobs were assigned but not yet running, so placement did not count those in-flight assignments.
- The remaining fallback nodes had equal scores, so placement fell through to a hash tie-breaker.
- For that run, the hash tie-breaker selected the same fallback node for every job.

Fix applied:

- `Scheduler` now tracks in-flight assignment load per node.
- Placement scoring includes both `runningJobs` and pending `assignmentLoad`.
- The score/tie-break/track operation is protected by a small `placementLock`.
- Raft `ASSIGN_JOB` proposal still happens outside the lock to avoid serializing slow consensus work.
- Assignment load is released on terminal states: `COMPLETED`, `FAILED`, `LOST`, and `CANCELLED`.
- Provisional assignment load is rolled back if the Raft proposal fails.
- `schedulerEpoch` is now atomic so concurrent scheduling gets stable reservation epochs.

Relevant files:

- `aegis-scheduler/src/main/java/com/aegisos/scheduler/Scheduler.java`
- `aegis-test-cluster/src/test/java/com/aegisos/cluster/HotArtifactSpreadTest.java`
- `aegis-test-cluster/src/test/java/com/aegisos/cluster/SchedulerDeterminismTest.java`

Validation performed:

```powershell
mvn -pl aegis-test-cluster -am "-Dtest=HotArtifactSpreadTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

```powershell
mvn -pl aegis-test-cluster -am "-Dtest=HotArtifactSpreadTest,SchedulerDeterminismTest,Phase5Test" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Both passed.

---

## 2. Latest Full Build Result

The user reran:

```powershell
mvn clean package 2>&1 | Tee-Object -FilePath build.log
```

Final result:

```text
[WARNING] Tests run: 83, Failures: 0, Errors: 0, Skipped: 1
BUILD SUCCESS
Total time: 17:04 min
Finished at: 2026-06-12T14:42:12+05:30
```

Important interpretation:

- The scheduler hotspot failure is fixed.
- Phase 5, Phase 6, Phase 8, Phase 9, and Phase 10 chaos tests completed.
- `LeaderFailoverCheckpointTest` passed.
- `JobCancellationTest` passed.
- `SchedulerDeterminismTest` passed.
- `HotArtifactSpreadTest` passed.

---

## 3. Expected Log Noise in Passing Builds

Several warnings/errors in `build.log` are expected because the tests intentionally break things.

### Expected Negative-Test Failures

These are expected when the corresponding tests pass:

- `ArtifactNotFoundTest`: job fails because the artifact/file does not exist.
- `MountPathTraversalTest`: job fails because mount paths like `/etc/passwd` or `../escaped.bin` are rejected.
- `CorruptCheckpointRecoveryTest`: worker fails on invalid checkpoint bytes.
- `CorruptSnapshotRecoveryTest`: snapshot load fails and falls back to full log replay.

These logs look scary but are the assertions being exercised.

### Expected Chaos/Partition Warnings

Common expected messages:

```text
partitioned from ...
not connected to ...
Replication requirement not met
Checkpoint sequence ... temporarily deferred
no known leader
RaftNode is shutting down
```

These appear in partition, failover, checkpoint, and chaos tests.
They usually mean the runtime refused to violate replication or fencing guarantees.

### SLF4J Provider Warnings

Some module-local unit test JVMs print:

```text
SLF4J(W): No SLF4J providers were found.
```

This is harmless for correctness. It only means that specific test classpath has `slf4j-api` without a logging backend.

---

## 4. Remaining Follow-Up Candidates

The suite passes, but the logs still contain cleanup/noise worth reducing later.

### 4.1 Repair/Audit During Shutdown

Example:

```text
Failed to propose REPAIR_CHUNK ...
RejectedExecutionException ...
ScheduledThreadPoolExecutor[Terminated]
```

This happened during `RaftQuorumIsolationTest` after a node was already shutting down.

Recommended follow-up:

- Make `RepairProposer` / audit scheduler treat terminated consensus as shutdown noise.
- Skip proposing repairs when the local node or Raft executor is stopping.
- Log compactly at debug/warn instead of full stack traces.

Likely files:

- `aegis-fs/src/main/java/com/aegisos/fs/audit/RepairProposer.java`
- `aegis-fs/src/main/java/com/aegisos/fs/audit/StorageAuditScheduler.java`
- `aegis-consensus/src/main/java/com/aegisos/consensus/RaftNode.java`

### 4.2 Checkpoint Retention Timeout Noise

Example:

```text
Failed to apply checkpoint retention ...
failed to commit file metadata: null
Caused by: TimeoutException
```

This happened during `LongRunningCheckpointChaosTest`, which still passed.

Recommended follow-up:

- Treat checkpoint retention delete failures as transient under leader failover/partition.
- Prefer compact logs for timeout/no-known-leader/raft-shutdown cases.
- Consider retrying retention opportunistically instead of treating it as immediate warning-level noise.

Likely file:

- `aegis-runtime/src/main/java/com/aegisos/runtime/ProcessRuntimeAgent.java`

### 4.3 Storage Unavailable Log Uploads

Examples:

```text
Skipped stdout log upload ... Replication requirement not met
Skipped stderr log upload ... no known leader
```

These are expected when tests kill or partition nodes while jobs complete.
The current behavior is correct: job completion is not failed merely because log upload could not meet storage replication during chaos.

Recommended follow-up:

- Keep current semantics.
- Optionally reduce repeated messages during high-churn chaos tests.

### 4.4 SLF4J Test Classpath

Optional cleanup:

- Add a test-scoped logging backend for modules that only have `slf4j-api`.
- This would remove `SLF4J(W): No SLF4J providers were found` from module unit test logs.

---

## 5. Developer Environment Note

During focused Maven runs, the VS Code Java language server sometimes raced Maven by touching or deleting class files under `target`.

Symptom:

```text
cannot access MessageType
class file for MessageType not found
cannot access AegisMessage
cannot access NodeId
```

This was not a source-code compile error. It was an IDE/Maven file-system race.

Workarounds:

- Close/restart the Java language server before clean full builds.
- Or run Maven with the Red Hat Java language server paused.
- If this appears, rerun the same Maven command before debugging source code.

---

## 6. Current Modified Areas

The stabilization pass touched or involved these areas:

- `aegis-consensus/src/main/java/com/aegisos/consensus/ConsensusModule.java`
- `aegis-fs/src/main/java/com/aegisos/fs/ChunkReplicator.java`
- `aegis-runtime/src/main/java/com/aegisos/runtime/JobRegistry.java`
- `aegis-runtime/src/main/java/com/aegisos/runtime/ProcessRuntimeAgent.java`
- `aegis-runtime/src/main/java/com/aegisos/runtime/ProcessSupervisor.java`
- `aegis-scheduler/src/main/java/com/aegisos/scheduler/ResourceAllocator.java`
- `aegis-scheduler/src/main/java/com/aegisos/scheduler/Scheduler.java`
- `aegis-test-cluster/src/test/java/com/aegisos/cluster/ClusterHarness.java`
- `aegis-test-cluster/src/test/java/com/aegisos/cluster/HotArtifactSpreadTest.java`
- `aegis-test-cluster/src/test/java/com/aegisos/cluster/JobCancellationTest.java`
- `aegis-test-cluster/src/test/java/com/aegisos/cluster/LeaderFailoverCheckpointTest.java`
- `aegis-test-cluster/src/test/java/com/aegisos/cluster/SchedulerDeterminismTest.java`

Before committing, review the full diff and ensure each change is intended.

---

## 7. Recommended Next Steps

1. Review the current `git diff` for all modified files.
2. Run one more full `mvn clean package` if any code changes are made after this handoff.
3. Optionally clean up remaining shutdown/audit log noise.
4. Consider adding a small unit-level scheduler test for in-flight assignment load, so `HotArtifactSpreadTest` is not the only guard.
5. Update release notes to mention scheduler burst placement stabilization and cancellation/checkpoint fencing hardening.

---

## 8. Bottom Line

AegisOS is currently in a much healthier state:

- Build completes.
- Full integration suite passes.
- Previous hang is gone.
- Hot artifact spread flake is fixed.
- Cancellation and checkpoint fencing are more deterministic.
- Remaining issues are mostly logging/lifecycle polish, not correctness failures.

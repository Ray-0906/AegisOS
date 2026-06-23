# PR-4 Subsystem Ownership & Wrong Clock

## Ownership Map
- **leader killed** -> External physical event
- **leader elected** -> **Raft** (Consensus)
- **recovery task visible** -> **Runtime** (JobSupervisor requeues)
- **job recovered** -> **Runtime** (Scheduler dispatches & Worker runs)
- **gossip DEAD** -> **Discovery** (Membership)

## The Wrong Clock
The original test performs this sequence:
1. harness.stop(leader)
2. ClusterHarness.await(90_000, () -> aliveNode.api().getProcessManager().status(jobId) == JobState.COMPLETED)
3. Assertions on liveNode.runtimeAgent().registry()

This is an architectural coupling/race condition. liveNode.api().getProcessManager().status(jobId) routes the request to the *leader* and reads the leader's Runtime state. Once the leader's state machine reaches COMPLETED, the awaiter unblocks.
However, the test then immediately asserts on liveNode.runtimeAgent().registry(), which is liveNode's *local replica* of the Runtime state. Because Raft replication is asynchronous, liveNode might not have applied the COMPLETED entry yet, leading to a race condition where the local executionId is stale.

Instead, we should use a proper test primitive (waitRecoveredJob) and assert on the authoritative state, or await the completion on the specific node's replica if we want to assert on its local registry.

## Action Plan
1. Add ClusterAwaiter.awaitJobRecovered(String jobId, Duration timeout) and ClusterHarness.isJobRecovered(String jobId).
2. Refactor LeaderFailoverDuringRecoveryTest to use this primitive, waiting for the job to complete on the new leader before asserting.
3. Configure egis.lease.duration.ms locally to speed up tests (e.g. 1000ms).
4. Move waitNodeDeath (gossip) to the end of the test.

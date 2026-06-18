# Task 2: Subsystem Ownership Mapping

- **kill** -> External physical event
- **leader elected** -> **Raft** (Consensus)
- **checkpoint visible** -> **Runtime** (JobRegistry)
- **checkpoint restored** -> **Runtime** (Worker execution & JobRegistry)
- **gossip DEAD** -> **Discovery** (Membership)

## The Wrong Clock
The original test synchronizes on the wrong clock in two places:
1. It polls leader.runtimeAgent().registry().getCheckpoint(jobId) directly instead of using a subsystem event primitive.
2. It waits for 
ewLeader.consensus().isLeader() (Raft clock) and then immediately asserts on the state of JobRegistry (Runtime clock). This violates INV-023 because the state machine application (Raft -> Runtime) is asynchronous. If the state machine is slightly slow, the 10s poll might flake, but more importantly, it's an architectural coupling.

Furthermore, the test does not even verify that the checkpoint is *restored* (that the job resumes on the new leader). It only verifies the log replicated. But because the lease expiration is 15s (egis.lease.duration.ms), if we add checkpoint_restored to the test, it times out mathematically due to the 20s test timeout.

## Action Plan
1. Add ClusterHarness.hasCheckpoint(jobId, minSeq) and ClusterAwaiter.awaitCheckpointVisible(jobId, minSeq).
2. Configure System.setProperty("aegis.lease.duration.ms", "2000") and System.setProperty("aegis.supervisor.interval.ms", "1000") in the test to simulate fast failover without changing system invariants.
3. Refactor LeaderFailoverCheckpointTest to use the awaiters and wait for the job to resume (waitCheckpointVisible with lastSeq + 1).
4. Move waitNodeDeath (gossip DEAD) to the very end of the test so it does not couple with Runtime recovery.

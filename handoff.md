# AegisOS v0.95 — Engineering Handoff

> Handoff for the next engineer/agent. Read this top-to-bottom before touching code.
> It captures **what exists, what was verified, the findings of the latest investigation, and exactly where to start.**

---

## 0. TL;DR

- **What it is:** AegisOS — a secure, peer-to-peer, distributed OS runtime in **Java 21**. Nodes authenticate, gossip membership, store files, and execute JAR artifacts as jobs.
- **Current Phase:** We are in the **Sprint 9.5 (Locality-Aware Scheduling)** verification and stabilization phase.
- **Milestone Status:** Sprints 1 through 8 are **COMPLETE and SIGNED OFF**. Sprint 9/9.5 implementation is complete, and the scheduler placement logic has been fixed.
- **Current Task:** The full integration test suite of 81+ tests is flaky under sequential execution, producing connection timeouts and a checkpoint retention policy failure. We have diagnosed these issues, and they are ready to be fixed.

---

## 1. Work Completed up to Sprint 9.5

### Sprints 1-7 ✅ SIGNED OFF
- **Sprints 1-5 (v0.5 Foundation):** Secure transport, Kad DHT routing, gossip membership, replicated file system (AegisFS), leader-driven storage audits, and two-phase chunk repair.
- **Sprint 6 (Snapshots & Compaction):** Log compaction using state machine snapshots; dynamic snapshot transfer to lagging/new nodes.
- **Sprint 7 (Job Execution & CLI):** Process-isolated execution of JAR artifacts; strict `JobState` machine lifecycle in `JobRegistry`; stdout/stderr logging persisted to AegisFS; dual-gate execution fencing.

### Sprint 8 ✅ SIGNED OFF
- Advanced execution lease-based failure detection, job cancellation, storage audit reality testing, and cluster stability under network partitions.

### Sprint 9 / 9.5: Locality-Aware Scheduling 🔄 IN PROGRESS / IMPLEMENTATION COMPLETE
- **Locality Scheduling:** Implemented scheduler scoring that accounts for both artifact cached bytes and checkpoint locality, preferring nodes that already hold local copies.
- **Scheduler Bug Fix:** Fixed a major bug in [Scheduler.java](file:///C:/Users/astra/Desktop/projects/AgeisOS/aegis-scheduler/src/main/java/com/aegisos/scheduler/Scheduler.java) where `ASSIGN_JOB` proposals had an empty `assignedNodeId` and state `PENDING` (corrected to copy `bestNode` and state `QUEUED`).
- **Isolation Verification:** Verified that targeted tests like `WorkerFailureResumeTest` and `LocalityMetricsValidationTest` pass when run in isolation.

---

## 2. Active Investigation: Test Suite Stabilization (June 11, 2026)

The full test suite execution runs ~81 tests sequentially in a single JVM and consistently fails with build failures due to test errors. Below is the current state of the investigation into the dominant failures.

### Issue A: "Read timed out" during Discovery / Seed Handshake
- **Symptom:** Multi-node cluster tests frequently fail with:
  ```
  Could not reach seed 127.0.0.1:60437: Read timed out
  New node ... did not join Gossip on leader ... within 30s
  ```
- **Context:** The timeout happens exactly 5 seconds after the node starts, which matches `CONNECT_TIMEOUT_MS = 5000` in `NetworkLayer.java`. In isolation, tests like `VoterPromotionTest` pass cleanly.
- **Evidence:** We also see `JobSupervisor shutdown complete: executor.isTerminated=false` repeatedly in the logs, indicating that virtual threads inside scheduled executors are failing to terminate or are getting starved.
- **Leading Theory (Virtual Thread Carrier Pinning):**
  AegisOS heavily utilizes Java 21 Virtual Threads for networking. Virtual threads share a small pool of OS carrier threads (ForkJoinPool). In Java 21, executing a `synchronized` block pins the virtual thread to its carrier thread.
  - `RaftLog.java` and `RaftMetadataStore.java` use `synchronized` methods extensively.
  - `RaftLog.append()` performs blocking file I/O (`FileOutputStream.write()`) inside a `synchronized` block.
  - Under heavy test load (hundreds of nodes doing Raft elections/appends simultaneously), the synchronous disk I/O inside `synchronized` blocks pins all available carrier threads.
  - This completely starves the virtual thread scheduler. When a new node attempts a discovery handshake, the `aegis-conn` virtual thread cannot be scheduled, causing the initiator to wait >5 seconds for the `HELLO` response, resulting in a `SocketTimeoutException`.
- **Current Actions Taken:**
  - We have added detailed `log.info` instrumentation to `HandshakeHandler.java` (`HANDSHAKE START`, `HELLO SENT`, `HELLO RECEIVED`) and `DiscoveryService.java` to trace exactly where the handshake stalls.

### Issue B: Checkpoint Retention Policy Test Failure
- **Symptom:** `CheckpointPersistenceTest.testCheckpointsAreWrittenAndRetained` occasionally fails with:
  ```
  Retention policy should cap checkpoints at 5 ==> expected: <true> but was: <false>
  ```
- **Proposed Fix:** Implement read-your-own-writes consistency. Modifying the `commit` method in `AegisFS.java` to block until the local node's `lastApplied` index catches up to the committed Raft index returned by `propose().get()` has been confirmed as the correct approach for this issue.

---

## 3. Where to Start for the Next Agent / Engineer

To complete Sprint 9.5 and fully stabilize the project, follow these steps:

### Step 1: Prove or Disprove the Carrier Pinning Theory
1. We have instrumented `HandshakeHandler` and `DiscoveryService`. Run the full test suite (`mvn clean package` or `mvn test -pl aegis-test-cluster -am`) and grep the output for `HANDSHAKE START` and `Could not reach seed`.
2. Determine exactly which handshake step (HELLO or VERIFY) is failing to send/receive.
3. If the handshake is simply starving, proceed to fix the locking.

### Step 2: Fix Virtual Thread Pinning in Consensus Storage
If carrier pinning is confirmed as the root cause:
1. Modify `RaftLog.java`: Replace all `synchronized` method signatures with a `java.util.concurrent.locks.ReentrantLock`. Reentrant locks do not pin carrier threads in Java 21.
2. Modify `RaftMetadataStore.java`: Replace its `synchronized` methods with a `ReentrantLock`.

### Step 3: Implement Read-Your-Own-Writes in AegisFS
Modify `AegisFS.java`'s `commit` method to block until the local node's state machine has applied the entry:
```java
    private void commit(StateCommand command) throws IOException {
        try {
            long index = consensus.propose(command).get(COMMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            long deadline = System.currentTimeMillis() + COMMIT_TIMEOUT_MS;
            while (consensus.raftNode().lastApplied() < index) {
                if (System.currentTimeMillis() > deadline) {
                    throw new java.util.concurrent.TimeoutException(
                        "Timeout waiting for local state machine to apply index " + index);
                }
                Thread.sleep(10);
            }
        } catch (Exception e) {
            throw new IOException("failed to commit file metadata: " + e.getMessage(), e);
        }
    }
```

### Step 4: Verify the Fixes
1. Run `mvn clean package` and verify that the `Read timed out` failures are eliminated and the 81+ integration tests pass sequentially.
2. Verify Sprint 9.5 metrics (`scheduler_download_bytes_saved`, `locality_wins`) as defined in the verification plan.

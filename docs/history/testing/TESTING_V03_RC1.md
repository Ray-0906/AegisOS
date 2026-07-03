# AegisOS v0.3 RC1 Demo & Testing Guide

This document provides a precise script for demonstrating the core value proposition of AegisOS: decentralized storage, dynamic artifact loading, and seamless fault-tolerance.

Follow these steps to record the v0.3 RC1 demo video or perform manual verification.

---

## Prerequisites
Ensure the project is built:
```bash
mvn clean package -DskipTests
```
You will need 4 separate terminal windows.

---

## 1. Start the Cluster

**Terminal 1 (Node 1 - Genesis):**
```bash
aegis start --node-id node1 --port 9001 --data-dir ./data/node1
```

**Terminal 2 (Node 2):**
```bash
aegis start --node-id node2 --port 9002 --data-dir ./data/node2 --seed localhost:9001
```

**Terminal 3 (Node 3):**
```bash
aegis start --node-id node3 --port 9003 --data-dir ./data/node3 --seed localhost:9001
```

*Wait 5 seconds for gossip to merge and the Raft leader to be elected.*

---

## 2. Upload Artifact

In **Terminal 4 (Client)**, upload an executable JAR. This demonstrates the `ArtifactRegistry` and `AegisFS`.

```bash
aegis artifact upload ./example-job.jar --seed localhost:9001
```

*Note the `SHA-256` output. Let's assume it is `abc123xyz`.*

---

## 3. Run Job

Submit a job targeting the artifact you just uploaded. The Scheduler on the Leader will assign this job to a worker.

```bash
aegis run --artifact abc123xyz --class com.example.MyJob --seed localhost:9001
```

*Observe the output in the worker terminal (e.g., Terminal 2). You will see it fetching chunks, caching the JAR, spinning up an isolated `ArtifactClassLoader`, and executing.*

---

## 4. Kill Worker & Watch Recovery

While the job is running (or immediately after submitting a long-running job), simulate a catastrophic worker failure:

1. Look at which node is executing the job (e.g., Terminal 2).
2. Hit `Ctrl+C` in that terminal to kill the worker hard.
3. **Observe:** Watch Terminal 1 and 3. Within seconds, the Kademlia gossip layer will mark Node 2 as `DEAD`.
4. **Observe:** The Scheduler running on the Leader detects the job's host is dead. It instantly reassigns the job to another healthy node.
5. **Observe:** The new node downloads the missing artifact chunks via AegisFS and resumes execution autonomously.

---

## 5. Kill Leader & Watch Election

Next, prove the control plane is fault-tolerant:

1. Identify the Raft Leader (usually Node 1 since it started first).
2. Hit `Ctrl+C` in the Leader's terminal.
3. **Observe:** Watch the remaining node. The Raft heartbeat timeout will trigger. An election will start, and the remaining node (with its vote) will establish a new quorum and become the new Leader.
4. **Verify:** Submit another job from the Client to prove the state machine and scheduler are still fully operational.

```bash
aegis run --artifact abc123xyz --class com.example.MyJob --seed localhost:9002
```

---

## 6. Show Completion

Finally, run the `status` command to show that despite two catastrophic node failures (including the leader), the system state is perfectly consistent and jobs completed successfully.

```bash
aegis status --seed localhost:9002
```

*Demo Complete.*

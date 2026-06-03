# AegisOS v0.2.0 Manual Testing Guide

This guide walks you through manually testing the Distributed Artifact Runtime features (v0.2.0) on your local machine using separate terminals. You will simulate a multi-node cluster, upload a job artifact, execute the job, and observe node failures and dynamic migration.

## Prerequisites

1. Ensure you have built the latest version of the project:
   ```bash
   mvn clean install -DskipTests
   ```
2. Build the Demo Job (if not already built). The Demo Job is included as a module inside `AgeisOS`:
   ```bash
   cd aegis-demo-job
   mvn clean package
   cd ../aegis-cli
   ```
3. Open **four** separate terminal windows side-by-side and navigate to `AgeisOS/aegis-cli` in all of them.

---

## Step 1: Start the Cluster

In the first three terminals, start the AegisOS nodes. They will automatically discover each other via gossip and elect a Raft leader.

**Terminal 1 (Node 1):**
```bash
java -jar target/aegis.jar start --home /tmp/aegis/node1 --port 7001
```

**Terminal 2 (Node 2):**
```bash
java -jar target/aegis.jar start --home /tmp/aegis/node2 --port 7002 --seed localhost:7001
```

**Terminal 3 (Node 3):**
```bash
java -jar target/aegis.jar start --home /tmp/aegis/node3 --port 7003 --seed localhost:7001
```

Wait a few seconds for the nodes to complete leader election. You should see logs indicating a new `LEADER`.

---

## Step 2: Upload the Artifact

In the **fourth** terminal, use the CLI to upload the compiled Demo Job JAR into the cluster. This will chunk, encrypt, and replicate the artifact using AegisFS, and register it in the Raft-backed Artifact Registry.

**Terminal 4 (CLI):**
```bash
java -jar target/aegis.jar artifact upload --seed localhost:7001 ../aegis-demo-job/target/aegis-demo-job-1.0.jar
```

*Note the `SHA-256` output. You will need it for the next step.*

---

## Step 3: Run the Job

Submit the job to the cluster. The scheduler will assign the job to the node with the lowest load. The chosen node will dynamically download the JAR, isolate it in a new ClassLoader, and begin execution.

**Terminal 4 (CLI):**
```bash
java -jar target/aegis.jar run --seed localhost:7001 --artifact <YOUR_SHA256_HASH_HERE> com.example.WordCounter
```

Watch the terminal of the node that was assigned the job. You will see logs indicating cache misses/hits, ClassLoader creation, and execution output.

---

## Step 4: Chaos and Migration (Optional)

To verify the resilience of the artifact runtime:

1. **Submit a long-running job:**
   ```bash
   java -jar target/aegis.jar run --seed localhost:7001 --artifact <YOUR_SHA256_HASH_HERE> com.example.LongRunningJob
   ```
2. **Kill the Worker:** While the job is running (watch the progress in the worker's terminal), press `Ctrl+C` in that terminal to kill the worker node.
3. **Observe Migration:** Watch the other terminals. The migration coordinator will detect the failure via gossip, fail the job, and re-schedule it on an alive node.
4. **Observe Recovery:** The new node will download the artifact from AegisFS, instantiate a fresh ClassLoader, restore the checkpoint from Raft, and resume the job from where it left off.

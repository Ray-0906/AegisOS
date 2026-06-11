# Getting Started with AegisOS

AegisOS is a secure, distributed operating system runtime built over a private P2P network. It provides fault-tolerant job execution, stateful checkpointing, an immutable artifact registry, intelligent locality-aware scheduling, and a distributed encrypted file system (AegisFS).

This guide walks you through building the project, starting a local cluster, and running your first fault-tolerant jobs.

---

## 1. Prerequisites

- **Java 21+** (Uses Virtual Threads and modern language features)
- **Apache Maven 3.8+**
- **Windows, Linux, or macOS**

---

## 2. Building the Project

AegisOS is built as a multi-module Maven project. To compile the code, run the tests, and build the runnable FAT JARs, execute the following from the root directory:

```bash
mvn clean package -DskipTests
```
*(Tests are comprehensive and test full cluster resilience; they take a few minutes to run. Skip them for a quick build).*

The primary executable we care about is the CLI, which is built into the `aegis-cli` module.

---

## 3. Starting a Local Cluster

AegisOS operates as a decentralized, peer-to-peer network. Nodes discover each other via a Kademlia DHT gossip protocol and achieve consensus using Raft. 

Let's start a 3-node cluster locally. Open three separate terminal windows.

**Terminal 1 (Node 1 - Bootstrap Node):**
```bash
# We use --bootstrap on the first node to initialize a new Raft cluster
java -jar aegis-cli/target/aegis-cli-1.0-SNAPSHOT-jar-with-dependencies.jar start --port 9000 --home ./node1 --bootstrap
```

**Terminal 2 (Node 2):**
```bash
# Connect to Node 1 via --seed
java -jar aegis-cli/target/aegis-cli-1.0-SNAPSHOT-jar-with-dependencies.jar start --port 9001 --home ./node2 --seed 127.0.0.1:9000
```

**Terminal 3 (Node 3):**
```bash
# Connect to Node 1 via --seed
java -jar aegis-cli/target/aegis-cli-1.0-SNAPSHOT-jar-with-dependencies.jar start --port 9002 --home ./node3 --seed 127.0.0.1:9000
```

*Wait a few seconds. You will see the nodes perform a Raft election and one node will emerge as the `<LEADER>`. The cluster is now active.*

---

## 4. Interacting via the CLI

Open a **fourth terminal** to act as your client workstation. The Aegis CLI interacts with the cluster by connecting to any seed node.

### Check Cluster Health
```bash
# List all alive nodes in the cluster
java -jar aegis-cli/target/aegis-cli-1.0-SNAPSHOT-jar-with-dependencies.jar nodes --seed 127.0.0.1:9000
```

### Upload an Artifact
AegisOS executes code from **Artifacts**. An artifact is a JAR file containing your executable code. The cluster will encrypt it, chunk it, and distribute it into `AegisFS`.

We have a pre-built demo job located at `aegis-demo-job/target/aegis-demo-job-1.0-SNAPSHOT.jar`.

```bash
java -jar aegis-cli/target/aegis-cli-1.0-SNAPSHOT-jar-with-dependencies.jar artifact upload aegis-demo-job/target/aegis-demo-job-1.0-SNAPSHOT.jar --seed 127.0.0.1:9000
```
**Save the SHA-256 ID** returned by this command. You will need it to run the job!

### Submit a Job
Let's run `CheckpointableSum`, a demo application that counts from 1 to 100, saving its state (checkpointing) every second.

Replace `<ARTIFACT_ID>` with the SHA-256 you just generated.
```bash
java -jar aegis-cli/target/aegis-cli-1.0-SNAPSHOT-jar-with-dependencies.jar run com.aegisos.demo.CheckpointableSum --artifact <ARTIFACT_ID> --seed 127.0.0.1:9000
```
This command returns a `Job ID` (e.g. `123e4567-e89b-12d3...`).

### Check Job Status
```bash
java -jar aegis-cli/target/aegis-cli-1.0-SNAPSHOT-jar-with-dependencies.jar status <JOB_ID> --seed 127.0.0.1:9000
```
This will tell you what node the job is running on, its state (`RUNNING`, `COMPLETED`), and how many checkpoints it has written.

---

## 5. Testing Fault Tolerance (Chaos Engineering!)

AegisOS provides **stateful failure recovery**. When a node dies, the jobs running on it are migrated to another node and resumed precisely from their last checkpoint.

Let's test this!

1. **Submit the `CheckpointableSum` job** again (as shown in Step 4).
2. **Quickly check its status** to find out which node it was scheduled on (e.g., Node 2).
3. **Go to that Node's terminal and kill it** (Press `Ctrl+C`).
4. **Check the job status again.**
   - You will see the job briefly transition to `LOST`.
   - The AegisOS Supervisor will detect the failure.
   - The Intelligent Scheduler will probe the remaining alive nodes. It will prefer nodes that currently hold the checkpoint chunks in `AegisFS`.
   - The job will transition back to `RUNNING` on a new node (e.g., Node 3).
5. Look at the logs of the new node. You will see it **resumes from the exact number** it left off on, rather than starting back at 1!

---

## 6. Storage & Clean up

AegisOS provisions an isolated workspace directory for every job execution. These are created under the node's `--home` directory (e.g., `./node1/workspaces/<job-id>/exec-1/`).

- **Artifacts** are transparently mounted (via symlinks or copies) into the workspace.
- **Scratch** directories are provided for temporary file IO.
- **Checkpoints** are automatically written securely into the distributed `AegisFS`.

When a job reaches a terminal state (`COMPLETED` or `FAILED`), the local workspace is gracefully cleaned up after a grace period. 

To shut down your cluster, simply hit `Ctrl+C` in all node terminals. Because `AegisFS` and the `Raft` log persist data to the `--home` directories, you can restart the nodes later and the cluster will seamlessly recover its state.

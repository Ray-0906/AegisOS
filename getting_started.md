# Getting Started with AegisOS v2.0.0

## Prerequisites
* Java 21 or higher
* Maven 3.8+

## 1. Bootstrapping a Local Cluster
Build the project:
```bash
mvn clean install -DskipTests
```

### Installation (Recommended)
- **Windows:** Run `.\install.ps1`
- **Linux/macOS:** Run `./install.sh`

*Important: Restart your terminal after installation to use the global command.*

Start the first node (the bootstrap node):
```bash
aegis start --bootstrap --rest-port 18000 --port 7000 --home node1-data
```

In a new terminal window, start a second node and join the cluster:

```bash
aegis start --seed 127.0.0.1:7000 --rest-port 18001 --port 7001 --home node2-data
```

## 2. Cluster Inspection
Verify the cluster is healthy and nodes can see each other:
```bash
aegis nodes --seed 127.0.0.1:18000
aegis leader --seed 127.0.0.1:18000
aegis cluster-health --seed 127.0.0.1:18000
```

## 3. Distributed File System Operations
You can use AegisFS as a highly available, decentralized storage system:
```bash
# Upload a file
aegis put path/to/local/file.txt --seed 127.0.0.1:18000

# List files
aegis ls --seed 127.0.0.1:18000

# Download a file
aegis get <FILE_ID> output.txt --seed 127.0.0.1:18000
```

## 4. Uploading an Executable Artifact

Before you can run a workload, you must upload the `.jar` binary to the artifact registry (which is backed by `AegisFS`):

```bash
aegis artifact upload path/to/your-job.jar --seed 127.0.0.1:18000
```
*Note the returned `Artifact ID` (a SHA-256 hash).*

**Example (Uploading a Java `.jar`):**
```bash
PS C:\> aegis artifact upload aegis-demo-job-1.0.jar --seed 127.0.0.1:18000
19:24:50.957 INFO  [main] c.a.c.LeaderResolver - Discovered leader: 6928e01... at http://127.0.0.1:18000
Uploaded aegis-demo-job-1.0.jar (artifact: 9e5cb04c2ce8b949cee7e44af4b121badd9a46f868aa91ac067b2f0fb4dac76d, size: 9720 bytes)
```

**Example (Uploading a Python `.py` script):**
```bash
PS C:\> aegis artifact upload agent_payload.py --seed 127.0.0.1:18000
19:24:59.667 INFO  [main] c.a.c.LeaderResolver - Discovered leader: 6928e01... at http://127.0.0.1:18000
Uploaded agent_payload.py (artifact: 55450d21017499c1960ef071303de3c592347aeac44bdd65ec0eb4958b150908, size: 368 bytes)
```

To verify it was registered:
```bash
aegis artifact list --seed 127.0.0.1:18000
```

To remove an artifact from the cluster:
```bash
aegis rm <ARTIFACT_ID> --seed 127.0.0.1:18000
```

## 5. Submitting a Process

Submit your workload to the cluster, requesting specific hardware resources:

```bash
aegis process submit --artifact <ARTIFACT_ID> --cpu 1 --memory 256 --seed 127.0.0.1:18000
```

**Example (Submitting a Java Process):**
```bash
PS C:\> aegis process submit --artifact 9e5cb04c2ce8b949cee7e44af4b121badd9a46f868aa91ac067b2f0fb4dac76d --cpu 1 --memory 256 --seed 127.0.0.1:18000
19:25:38.405 INFO  [main] c.a.c.LeaderResolver - Discovered leader: 6928e01... at http://127.0.0.1:18000
b9afc5fd-d51b-4566-80af-df48e0bd36a5
```

By default, the process runs via the JVM (`java -jar {artifact}`). However, AegisOS v2.0.0 is a Polyglot Engine. You can execute Python, Node.js, bash scripts, or native binaries by supplying a custom command:

```bash
aegis process submit --artifact <ARTIFACT_ID> --command "python {artifact}" --cpu 1 --memory 256 --seed 127.0.0.1:18000
```

**Example (Submitting a Python Process):**
```bash
PS C:\> aegis process submit --artifact 55450d21017499c1960ef071303de3c592347aeac44bdd65ec0eb4958b150908 --command "python {artifact}" --cpu 1 --memory 256 --seed 127.0.0.1:18000
19:27:26.139 INFO  [main] c.a.c.LeaderResolver - Discovered leader: 6928e01... at http://127.0.0.1:18000
1c9f7d0d-5ea4-4fed-9eb8-b7a3996a0444
```

To pipe the output of this process to another process running on the cluster (Virtual IPC Overlay), provide the target `--pipe-to` Process ID:

```bash
aegis process submit --artifact <ARTIFACT_ID> --command "python {artifact}" --pipe-to <RECEIVER_PROCESS_ID> --cpu 1 --memory 256 --seed 127.0.0.1:18000
```

The scheduler will evaluate the Gossip topology, find a node with 256MB of free RAM, and assign the process.

## 6. Monitoring, Logs, and Control

Check the status of your distributed process (`PLACED`, `RUNNING`, `COMPLETED`, etc.):

```bash
aegis process status <PROCESS_ID> --seed 127.0.0.1:18000
```

**Example:**
```bash
PS C:\> aegis process status 1c9f7d0d-5ea4-4fed-9eb8-b7a3996a0444 --seed 127.0.0.1:18000
19:27:35.229 INFO  [main] c.a.c.LeaderResolver - Discovered leader: 6928e01... at http://127.0.0.1:18000
Process ID: 1c9f7d0d-5ea4-4fed-9eb8-b7a3996a0444
Artifact ID: 55450d21017499c1960ef071303de3c592347aeac44bdd65ec0eb4958b150908
State: RUNNING
Node: 72dc83ec91a487b911f9ae851c06db9280f769497f5044395d7b3ee78ac5ff5f
Resources: 1 cores, 256 MB
```

To list all historical and running jobs:
```bash
aegis process list --seed 127.0.0.1:18000
```


To forcefully terminate the process:

```bash
aegis process cancel <PROCESS_ID> --seed 127.0.0.1:18000
```

To view or stream the logs of a process:

```bash
aegis logs <PROCESS_ID> --seed 127.0.0.1:18000
```

To follow the logs (similar to `tail -f`):

```bash
aegis logs -f <PROCESS_ID> --seed 127.0.0.1:18000
```

## Full CLI Command Reference
Here is a comprehensive list of commands supported by the `aegis` CLI tool:
* `start`: Start an AegisOS node in this process.
* `info`: Show this node's identity and configuration.
* `nodes`: List alive cluster nodes.
* `put`: Upload a local file into the cluster file system.
* `get`: Download a file from the cluster file system.
* `ls`: List files stored in the cluster.
* `run`: Submit a job to the cluster (alias for `process submit`).
* `status`: Show the status of a submitted job.
* `artifact`: Manage cluster artifacts (`upload`, `list`).
* `allocator`: Manage and inspect resource allocator.
* `admin`: Manage cluster administration tasks.
* `jobs`: Job management commands.
* `cluster-health`: Show aggregated cluster health.
* `health`: Show the health status of the cluster subsystems.
* `leader`: Show the cluster leader.
* `process`: Process management commands (`submit`, `list`, `status`, `cancel`).
* `logs`: Stream process logs.
* `rm`: Remove an artifact from the cluster.

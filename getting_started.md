# Getting Started with AegisOS v1.0.0

## Prerequisites
* Java 21 or higher
* Maven 3.8+

## 1. Bootstrapping a Local Cluster
Build the project and start the first node (the bootstrap node):
```bash
mvn clean install -DskipTests
java -jar aegis-cli/target/aegis.jar start --bootstrap --api-port 20000 --port 7000 --data-dir node1-data
```

In a new terminal window, start a second node and join the cluster:

```bash
java -jar aegis-cli/target/aegis.jar start --seed 127.0.0.1:7000 --api-port 20001 --port 7001 --data-dir node2-data
```

## 2. Cluster Inspection
Verify the cluster is healthy and nodes can see each other:
```bash
java -jar aegis-cli/target/aegis.jar nodes --seed 127.0.0.1:20000
java -jar aegis-cli/target/aegis.jar leader --seed 127.0.0.1:20000
java -jar aegis-cli/target/aegis.jar cluster-health --seed 127.0.0.1:20000
```

## 3. Distributed File System Operations
You can use AegisFS as a highly available, decentralized storage system:
```bash
# Upload a file
java -jar aegis-cli/target/aegis.jar put path/to/local/file.txt --seed 127.0.0.1:20000

# List files
java -jar aegis-cli/target/aegis.jar ls --seed 127.0.0.1:20000

# Download a file
java -jar aegis-cli/target/aegis.jar get <FILE_ID> output.txt --seed 127.0.0.1:20000
```

## 4. Uploading an Executable Artifact

Before you can run a workload, you must upload the `.jar` binary to the artifact registry (which is backed by `AegisFS`):

```bash
java -jar aegis-cli/target/aegis.jar artifact upload path/to/your-job.jar --seed 127.0.0.1:20000
```
*Note the returned `Artifact ID` (a SHA-256 hash).*

To verify it was registered:
```bash
java -jar aegis-cli/target/aegis.jar artifact list --seed 127.0.0.1:20000
```

## 5. Submitting a Process

Submit your workload to the cluster, requesting specific hardware resources:

```bash
java -jar aegis-cli/target/aegis.jar process submit --artifact <ARTIFACT_ID> --cpu 1 --memory 256 --seed 127.0.0.1:20000
```

The scheduler will evaluate the Gossip topology, find a node with 256MB of free RAM, and assign the process.

## 6. Monitoring, Logs, and Control

Check the status of your distributed process (`PLACED`, `RUNNING`, `COMPLETED`, etc.):

```bash
java -jar aegis-cli/target/aegis.jar process status <PROCESS_ID> --seed 127.0.0.1:20000
```

To list all historical and running jobs:
```bash
java -jar aegis-cli/target/aegis.jar process list --seed 127.0.0.1:20000
```

To stream the live standard output from the executing node across the network:

```bash
# Wait for the process to be RUNNING before requesting logs
java -jar aegis-cli/target/aegis.jar logs <PROCESS_ID> --seed 127.0.0.1:20000
```

To forcefully terminate the process:

```bash
java -jar aegis-cli/target/aegis.jar process cancel <PROCESS_ID> --seed 127.0.0.1:20000
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
* `logs`: Stream live logs for a running process.

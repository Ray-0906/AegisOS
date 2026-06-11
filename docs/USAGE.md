# AegisOS Usage Guide

AegisOS is a secure, distributed P2P operating system runtime that uses Raft for consensus, Kademlia for discovery, and provides a globally distributed file system and job scheduler.

This document explains how to build the project, run a cluster locally, and interact with the cluster using the `aegis-cli` tool.

---

## 1. Prerequisites

- **Java 21** or higher.
- **Maven 3.8+** installed and available on your `PATH`.

---

## 2. Building the Project

To build AegisOS from source, run:

```bash
mvn clean package
```

This will compile all modules, run the test suite, and generate shaded, executable JAR files in the respective `target` directories.

The two main executable JARs you will use are:
1. **Node daemon**: `aegis-node/target/aegis-node-0.1.0-SNAPSHOT-shaded.jar`
2. **CLI client**: `aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar`

---

## 3. Starting a Local Cluster

AegisOS clusters require at least one **seed node** to bootstrap the network. Subsequent nodes will connect to this seed node to join the cluster.

### Start the Seed Node (Bootstrap)

Start the first node and tell it to bootstrap a new cluster:

```bash
java -jar aegis-node/target/aegis-node-0.1.0-SNAPSHOT-shaded.jar \
  --home node1_home \
  --port 5001 \
  --bootstrap
```

- `--home`: Specifies the directory where the node stores its Raft log, distributed filesystem chunks, and identity keys.
- `--port`: The TCP port the node binds to for cluster communication.
- `--bootstrap`: Tells the node to start a new Raft cluster (it will instantly become the leader).

### Start Additional Nodes

Once the seed node is running, start a second and third node. Tell them to connect to the seed node (`127.0.0.1:5001`).

**Node 2:**
```bash
java -jar aegis-node/target/aegis-node-0.1.0-SNAPSHOT-shaded.jar \
  --home node2_home \
  --port 5002 \
  --seed 127.0.0.1:5001
```

**Node 3:**
```bash
java -jar aegis-node/target/aegis-node-0.1.0-SNAPSHOT-shaded.jar \
  --home node3_home \
  --port 5003 \
  --seed 127.0.0.1:5001
```

They will automatically:
1. Exchange cryptographic keys via Handshake.
2. Be added to the Raft consensus group by the leader.
3. Synchronize the state machine and join the cluster.

---

## 4. Using the Aegis CLI

The `aegis-cli` tool is used to interact with the cluster. By default, it connects to `127.0.0.1:5001`. You can specify a different endpoint using `--endpoint`.

### Cluster Administration

**View Cluster Info:**
Shows the currently connected seed node, cluster size, and consensus leader.
```bash
java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar info
```

**List Nodes:**
Lists all nodes in the Kademlia routing table.
```bash
java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar nodes
```

**Raft Consensus Info:**
Shows the current term, the leader ID, and cluster configuration.
```bash
java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar raft
```

### Distributed File System (AegisFS)

AegisFS is a content-addressed storage system. Files are split into 1MB chunks, encrypted, and replicated across the cluster.

**Upload a File:**
```bash
java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar put /local/path/to/file.txt
```
*Returns an Artifact ID (e.g., `aegis://artifact/123456...`).*

**List Uploaded Files:**
```bash
java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar ls
```

**Download a File:**
```bash
java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar get <Artifact_ID> /local/path/downloaded.txt
```

### Distributed Job Execution

AegisOS can run sandboxed Java jobs across the cluster. The scheduler uses locality-awareness to run jobs on nodes that already have the required artifacts or checkpoints cached.

**Submit a Job:**
```bash
java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar run <Artifact_ID>
```
*Returns a Job ID.*

**Check Job Status:**
```bash
java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar status <Job_ID>
```

**List All Jobs:**
```bash
java -jar aegis-cli/target/aegis-cli-0.1.0-SNAPSHOT-shaded.jar jobs
```

---

## 5. Locality-Aware Scheduling

AegisOS automatically tracks where file chunks and job checkpoints reside on the network. When you submit a job that depends on an artifact, the `Scheduler` queries all nodes for their `download_bytes_saved` score.

The scheduler assigns the job to the node that already holds the artifact chunks or checkpoint state locally, drastically reducing network I/O and recovery time.

## 6. Development and Testing

AegisOS is built with a custom `ClusterHarness` for integration testing. To run the full validation suite:

```bash
mvn test -pl aegis-test-cluster
```

*(Note: The test cluster uses virtual threads and in-memory simulated networks for lightning-fast execution.)*

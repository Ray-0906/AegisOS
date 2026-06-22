# AegisOS v1.3

A secure, distributed operating-system runtime over a private peer-to-peer network.
Every node is identical and autonomous; there is no central server. Nodes discover each
other, agree on shared state via Raft, store files redundantly in AegisFS, and run jobs
that survive machine failure with checkpoint recovery and locality-aware scheduling.

**Release notes:** [RELEASE_NOTES_v1.0_RC1.md](RELEASE_NOTES_v1.0_RC1.md)

---

## 1. Architecture: The Shift to Platform Engineering

In AegisOS v1.0, the architecture operates with strict boundary separation between internal cluster networking and external client interactions. 

### Pure REST Clients
**The CLI no longer joins the cluster as a peer.** All client commands (`nodes`, `put`, `get`, `run`, etc.) act as pure REST HTTP clients. They communicate exclusively over the HTTP API and are completely decoupled from internal Raft/Gossip mechanisms.

### Port Conventions
A single AegisOS node runs three separate network layers. **When you pass `--seed` to a client command, you must use the REST port.**

| Layer | Offset | Example (if Node started on 9001) | Purpose |
|-------|--------|-----------------------------------|---------|
| **P2P Transport** | Base | `9001` | Internal Gossip, Raft Consensus, Data Replication |
| **Metrics** | +10000 | `19001` | Prometheus Metrics Exporter |
| **REST API** | +11000 | `20001` | Client Operations, API Server |

---

## 2. Quick Start (~5 minutes)

**Requirements:** JDK 21+, Maven 3.9+

### Step 1: Build
```bash
mvn clean package
```
*Produces the CLI JAR at `aegis-cli/target/aegis.jar`.*

### Step 2: Start a 3-node cluster
Open three terminals. Note that nodes form a cluster by pointing to a seed node's **P2P Port** (`9001`).

**Node 1 (Seed):**
```bash
java -jar aegis-cli/target/aegis.jar start --home node1 --port 9001 --bootstrap
```

**Node 2:**
```bash
java -jar aegis-cli/target/aegis.jar start --home node2 --port 9002 --seed 127.0.0.1:9001
```

**Node 3:**
```bash
java -jar aegis-cli/target/aegis.jar start --home node3 --port 9003 --seed 127.0.0.1:9001
```

---

## 3. Comprehensive Feature Guide & CLI Reference

For all client commands below, you must point `--seed` to the **REST Port** of any running node. If Node 1 was started on `9001`, its REST port is `20001`.

### 3.1 Cluster Information & Observability

**List Cluster Nodes**
Lists all nodes currently alive in the Gossip membership.
```bash
java -jar aegis-cli/target/aegis.jar nodes --seed 127.0.0.1:20001
```

**Show the Cluster Leader**
Returns the node ID of the current Raft leader.
```bash
java -jar aegis-cli/target/aegis.jar leader --seed 127.0.0.1:20001
```

**Node Health**
Shows detailed health metrics for a specific node's subsystems (Raft, storage, memory).
```bash
java -jar aegis-cli/target/aegis.jar health --seed 127.0.0.1:20001
```

**Cluster Health**
Shows aggregated health information across the entire cluster.
```bash
java -jar aegis-cli/target/aegis.jar cluster-health --seed 127.0.0.1:20001
```

**Node Identity Info**
Shows the current node's identity and configuration. This command reads local files and does not connect to the cluster.
```bash
java -jar aegis-cli/target/aegis.jar info --home node1
```

---

### 3.2 Distributed File System (AegisFS)

AegisFS is a content-addressed, distributed storage system.

**Upload a File (Put)**
Uploads a local file and replicates it across the cluster.
```bash
echo "Hello AegisOS" > hello.txt
java -jar aegis-cli/target/aegis.jar put hello.txt /hello.txt --seed 127.0.0.1:20001
```

**Download a File (Get)**
Downloads a file from the cluster.
```bash
java -jar aegis-cli/target/aegis.jar get /hello.txt recovered.txt --seed 127.0.0.1:20001
```

**List Files (Ls)**
Lists all files stored in the cluster.
```bash
java -jar aegis-cli/target/aegis.jar ls / --seed 127.0.0.1:20001
```

---

### 3.3 Artifact Management

Artifacts are compiled executable JARs that contain your Jobs.

**Upload a Job Artifact**
Uploads a compiled JAR to the cluster's artifact registry. It returns a `SHA256` artifact ID.
```bash
java -jar aegis-cli/target/aegis.jar artifact upload my-job.jar --seed 127.0.0.1:20001
```

**List Artifacts**
Lists all registered executable artifacts.
```bash
java -jar aegis-cli/target/aegis.jar artifact list --seed 127.0.0.1:20001
```

---

### 3.4 Distributed Job Execution

AegisOS runs distributed jobs that survive node failures via checkpointing.

**Run a Job**
Submits a job for execution. You must provide the fully qualified class name. Optionally, specify an `--artifact` SHA256 (from `artifact upload`).
```bash
java -jar aegis-cli/target/aegis.jar run com.example.MyJob --artifact <SHA256_ID> --seed 127.0.0.1:20001
```

**Check Job Status**
Shows the current status (QUEUED, RUNNING, COMPLETED, FAILED) and assigned worker node of a job.
```bash
java -jar aegis-cli/target/aegis.jar status <JOB_ID> --seed 127.0.0.1:20001
```

**List All Jobs**
Lists all active and historical jobs in the cluster.
```bash
java -jar aegis-cli/target/aegis.jar jobs list --seed 127.0.0.1:20001
```

**Get Job Logs**
Streams the stdout/stderr logs of a completed or running job.
```bash
java -jar aegis-cli/target/aegis.jar jobs logs <JOB_ID> --seed 127.0.0.1:20001
```

**Cancel a Job**
Cancels a running or queued job.
```bash
java -jar aegis-cli/target/aegis.jar jobs cancel <JOB_ID> --seed 127.0.0.1:20001
```

---

### 3.5 Administration

**Add a Voting Member**
Promote an existing node to be a full Raft voting member.
```bash
java -jar aegis-cli/target/aegis.jar admin add-member <NODE_ID> --seed 127.0.0.1:20001
```

**Remove a Voting Member**
Demote or remove a node from the Raft quorum.
```bash
java -jar aegis-cli/target/aegis.jar admin remove-member <NODE_ID> --seed 127.0.0.1:20001
```

---

## 4. Tests and Validation

We employ strict integration tests that boot full clusters in-process.

**Run the Full Integration Suite (18 mins)**
Validates all system invariants, replication, snapshotting, and the new REST APIs.
```bash
mvn clean verify
```

> **Known Build Caveat:**
> Avoid running `mvn test verify` after a previous reactor build without a `clean` phase. The `maven-compiler-plugin`'s incremental compiler drops fully-qualified package mappings across module boundaries. Always use `mvn clean verify`.

---

## Further reading

| Document | Purpose |
|----------|---------|
| [RELEASE_NOTES_v1.0_RC1.md](RELEASE_NOTES_v1.0_RC1.md) | What changed in this release |
| [docs/USAGE.md](docs/USAGE.md) | Extended CLI reference (jobs, artifacts, raft) |
| [docs/ARCHITECTURE_v0.95.md](docs/ARCHITECTURE_v0.95.md) | Architecture diagrams and flows |
| [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) | Current limitations and deferred work |
| [handoff.md](handoff.md) | Engineering handoff (stabilization investigation) |
| [docs/post-v1-roadmap.md](docs/post-v1-roadmap.md) | Post-v1.0 deferred items |

---

## Modules

| Module | Responsibility |
| --- | --- |
| `aegis-core` | Shared types, crypto, identity, protobuf schema |
| `aegis-network` | Encrypted transport (handshake + AES-256-GCM) |
| `aegis-discovery` | Gossip membership + Kademlia DHT |
| `aegis-consensus` | Raft leader election, log replication |
| `aegis-fs` | Content-addressed distributed file system |
| `aegis-scheduler` | Locality-aware weighted job placement |
| `aegis-runtime` | Job execution, checkpointing, migration |
| `aegis-api` | Public REST API boundaries and HTTP Server |
| `aegis-client` | Stateless HTTP Client for REST endpoints |
| `aegis-node` | Boots and wires all layers into one JVM |
| `aegis-cli` | The `aegis` command-line interface |
| `aegis-test-cluster` | In-process multi-node integration tests |

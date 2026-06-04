# AegisOS Architecture

AegisOS is a distributed cluster operating system built for resilience, high availability, and self-healing. It merges consensus-based state management, a decentralized file system, and distributed job scheduling into a cohesive, peer-to-peer network runtime.

## Core Principles

1. **Decentralized Autonomy**: No single point of failure. The cluster negotiates state, file placement, and scheduling automatically.
2. **Self-Healing**: If a node fails, the cluster automatically repairs under-replicated data and restarts interrupted jobs on healthy nodes.
3. **Immutability**: Files and artifacts are content-addressed (hashed), preventing modification and providing trivial verification.

## System Components

### 1. Discovery and Gossip (Kademlia DHT)
Nodes discover each other using a Kademlia-based Distributed Hash Table (DHT). Gossip protocols are used to propagate node liveness (`ALIVE` vs `DEAD`) and storage capabilities rapidly across the cluster. This avoids requiring a centralized "registry" node.

### 2. Distributed Consensus (Raft)
AegisOS implements the Raft consensus algorithm for cluster-wide coordination. The Raft log acts as the singular source of truth for:
* **File Metadata** (`REGISTER_FILE`, `REGISTER_ARTIFACT`)
* **Job State** (`ASSIGN_JOB`, `UPDATE_JOB`)

Only "voting" nodes participate in Raft quorum decisions. If a node is marked `DEAD` by gossip, the cluster automatically recomputes its voting quorum without that node, maintaining liveness even when multiple nodes fail.

### 3. Distributed File System (AegisFS)
AegisFS stores data across the cluster with an explicit Replication Factor (e.g., `RF=3`).
* **Chunking**: Large files are broken into encrypted chunks.
* **Storage**: Chunks are scattered to various peers based on DHT distance to the chunk hash.
* **Replication**: The system ensures chunks are written to at least `RF` distinct nodes before confirming an upload.
* **Metadata**: The layout of the file (chunk hashes, nonces, and encryption keys) is stored immutably in the Raft state machine.

### 4. Self-Healing Storage
The `SelfHealingReaper` continuously runs in the background of each node. It scans the local `ChunkStore` and compares the alive holders of each chunk against the Raft `FileMetadata`.
* If the number of alive holders falls below `RF` (due to node death), the node with the lowest ID automatically initiates a re-replication of the chunk to a healthy node.
* It then updates the Raft `FileMetadata` to record the chunk's new location, restoring the system to a fully replicated state without manual intervention.

### 5. Distributed Scheduler
Jobs are submitted using an artifact ID and execution arguments.
* The API forwards the job to the current Raft Leader.
* The Leader selects a healthy node and commits an `ASSIGN_JOB` command to the Raft log.
* The assigned node detects its task, downloads the necessary artifact chunks from peers (caching it locally), and instantiates the job inside an isolated virtual thread via `ProcessRuntimeAgent`.
* Job status changes (`QUEUED` -> `RUNNING` -> `COMPLETED`/`FAILED`) are continuously synced back into the Raft log.

## Typical Workflows

### Uploading an Artifact
1. The client connects to any node.
2. The file is split, encrypted, and its chunks are pushed to 3 healthy storage nodes via Kademlia routing.
3. The uploading node proposes a `REGISTER_ARTIFACT` command to the Raft leader.
4. The leader commits the metadata, and the artifact becomes globally addressable.

### Running a Job
1. The client submits a run request referencing the artifact hash.
2. The Raft leader assigns the job to a worker.
3. The worker's `ArtifactCache` looks up the chunk holders from the Raft `ArtifactRegistry`.
4. The worker requests chunks over TCP, decrypts them, and reassembles the jar.
5. The `JobExecutor` runs the jar main class and reports the result.

### Handling Node Failure
1. A node process terminates.
2. The `MembershipService` on surviving nodes marks it `DEAD` after 2 gossip ping failures.
3. The Raft quorum shrinks automatically to ignore the dead node.
4. If the dead node was the leader, Raft auto-elects a new leader within milliseconds.
5. The `SelfHealingReaper` kicks in, notices the dead node's chunks are under-replicated, and pushes copies to surviving nodes.
6. If the dead node was executing a long-running job, future versions of AegisOS will detect the stale `RUNNING` state and re-queue the job onto a healthy node (Checkpoint Migration).

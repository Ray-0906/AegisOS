# AegisOS

AegisOS is a decentralized compute and storage runtime written in Java. It combines distributed execution, consensus-backed metadata, and autonomous failure recovery into a single, cohesive operating system layer.

Unlike traditional orchestrators that rely on central control planes, AegisOS nodes operate collectively. You can submit an artifact to any node, and the cluster will dynamically schedule it, execute it, and preserve its state—even as nodes join, fail, or leave the network.

## Table of Contents
- [System Capabilities](#system-capabilities)
- [Theory and Architecture](#theory-and-architecture)
- [Architecture Modules](#architecture-modules)
- [Available Commands & Services](#available-commands--services)
- [Documentation](#documentation)

## System Capabilities

AegisOS v2.0.0 is capable of the following distributed operations:

* **Native Artifact Execution:** Dynamically distribute and execute standard Java `.jar` binaries across a network of physical machines without external container runtimes.
* **Polyglot Workload Execution:** Execute non-JVM payloads (Python, Node.js, bash, C++) using a dynamic `executionCommand` schema and `{artifact}` injection.
* **Hardware-Aware Load Balancing:** Automatically measure and evaluate physical JVM heap limits (`maxMemory`) and CPU cores, routing workloads only to nodes with verified capacity to prevent OS-level Out-Of-Memory (OOM) crashes.
* **Cryptographic Workload Routing:** Securely map workloads to nodes using stable 32-byte cryptographic hashes (`NodeId`) derived from Ed25519 public keys, completely eliminating split-brain scheduling collisions.
* **Virtual IPC Overlay Routing:** Chain distributed workloads across network boundaries. `stdout` of one process can seamlessly pipe into the `stdin` of another process running on a remote node over the TCP transport layer.
* **Distributed Stream Multiplexing:** Live-stream `stdout` and `stderr` from a remote executing process back to the original submitter's CLI, chunked and multiplexed over the core TCP transport layer.
* **Stateful Checkpointing & Injection:** Long-running workloads can persist binary checkpoints into the Raft state machine, which are automatically re-injected by the `LocalRuntimeEngine` on recovery.
* **Autonomous Garbage Collection:** Automatically detect hard node crashes or network partitions via Gossip protocol timeouts, forcefully transitioning orphaned "Ghost Workloads" to a safe failure state.
* **Asynchronous State Mutations:** Process hundreds of state transitions concurrently via a reactive, lock-free Pub/Sub event bus that never blocks the underlying Raft consensus engine.

## Theory and Architecture

AegisOS is built on the philosophy of a "single system image" where a network of independent nodes forms a seamless, cohesive operating system. It moves away from the master-worker paradigm in favor of a fully symmetric, leaderless (from the client's perspective) architecture. 

Under the hood, AegisOS relies on a combination of distributed systems primitives:
1. **Raft Consensus:** Guarantees strong consistency for the global state (metadata, job scheduling, filesystem records).
2. **Gossip Protocol:** Provides scalable, eventually-consistent topology discovery and hardware telemetry sharing without overloading the consensus leader.
3. **Kademlia-inspired Routing:** Leverages cryptographic node identities (`NodeId`) for stable and deterministic network addressing.
4. **Reactive Event Loop:** Separates the fast, synchronous consensus log from the slow, physical realities of process execution using asynchronous Pub/Sub buses.

## Architecture Modules
* `aegis-consensus`: Raft-based distributed state machine.
* `aegis-discovery`: Gossip protocol for eventually-consistent peer topology and hardware telemetry.
* `aegis-network`: Multiplexed TCP transport layer with Virtual IPC streaming.
* `aegis-fs`: Distributed file system for immutable artifact storage.
* `aegis-runtime`: The physical OS process supervisor and scheduling daemon.

## Project File Structure

```text
AegisOS/
├── aegis-api/           # REST APIs and DTO contracts
├── aegis-cli/           # Command-Line Interface and PicoCLI commands
├── aegis-client/        # HTTP client for interacting with the AegisOS cluster
├── aegis-consensus/     # Raft-based distributed state machine implementation
├── aegis-core/          # Core models, configuration, and shared utilities
├── aegis-discovery/     # Gossip protocol for peer topology and telemetry
├── aegis-fs/            # Distributed file system for immutable artifacts
├── aegis-network/       # Multiplexed TCP transport layer & Virtual IPC
├── aegis-node/          # The main application entrypoint tying all modules together
├── aegis-runtime/       # Physical OS process supervisor and scheduling daemon
├── aegis-scheduler/     # Workload placement and hardware-aware load balancing
├── aegis-demo-job/      # Example Java payloads for testing
├── aegis-examples/      # Reference implementations of cluster interactions
├── aegis-contract-test/ # Integration and contract tests across module boundaries
├── aegis-test-cluster/  # Framework for spinning up in-memory mock clusters
└── docs/                # Architecture Decision Records (ADRs) and documentation
```


## Available Commands & Services

The AegisOS CLI exposes a rich set of commands to interact with the cluster:

### Node & Cluster Management
* `start`: Start an AegisOS node in the current process.
* `info`: Show the local node's cryptographic identity and configuration.
* `nodes`: List all currently alive nodes discovered via Gossip.
* `leader`: Identify the current Raft consensus leader.
* `health`: Show detailed health status of all internal subsystems (Raft, Gossip, Storage).
* `cluster-health`: Show aggregated cluster-wide health.
* `admin`: Manage cluster administration tasks (e.g., forcing a leader step-down).

### Distributed File System (AegisFS)
* `put`: Upload a local file into the distributed file system.
* `get`: Download a file from the cluster to the local machine.
* `ls`: List files currently stored and replicated in the cluster.

### Artifacts & Execution
* `artifact upload`: Securely upload a `.jar` binary artifact for cluster execution.
* `artifact list`: List available executable artifacts in the registry.
* `process submit` (or `run`): Submit a new job to the cluster scheduler with specific CPU and memory requirements.
* `process status` (or `status`): Check the real-time state of a submitted job (`PLACED`, `RUNNING`, `COMPLETED`, `FAILED`).
* `process list` (or `jobs`): List all running and historical processes.
* `process cancel`: Forcefully terminate an executing process across the network.
* `logs`: Connect to the Virtual IPC overlay to stream live `stdout`/`stderr` from an executing remote process.

### Resource Allocation
* `allocator`: Manage and inspect the distributed resource allocator.

## Documentation
* [Getting Started Guide](getting_started.md)
* [Internal Workings & Subsystem Mechanics](docs/architecture/internal_workings.md)
* [The Execution Loop](docs/architecture/execution_loop.md)
* [Changelog](CHANGELOG.md)

# AegisOS

AegisOS is a decentralized compute and storage runtime written in Java. It combines distributed execution, consensus-backed metadata, and autonomous failure recovery into a single, cohesive operating system layer.

Unlike traditional orchestrators that rely on central control planes, AegisOS nodes operate collectively. You can submit an artifact to any node, and the cluster will dynamically schedule it, execute it, and preserve its state—even as nodes join, fail, or leave the network.

## System Capabilities

AegisOS v1.0.0 is capable of the following distributed operations:

* **Native Artifact Execution:** Dynamically distribute and execute standard Java `.jar` binaries across a network of physical machines without external container runtimes.
* **Hardware-Aware Load Balancing:** Automatically measure and evaluate physical JVM heap limits (`maxMemory`) and CPU cores, routing workloads only to nodes with verified capacity to prevent OS-level Out-Of-Memory (OOM) crashes.
* **Cryptographic Workload Routing:** Securely map workloads to nodes using stable 32-byte cryptographic hashes (`NodeId`) derived from Ed25519 public keys, completely eliminating split-brain scheduling collisions.
* **Distributed Stream Multiplexing:** Live-stream `stdout` and `stderr` from a remote executing process back to the original submitter's CLI, chunked and multiplexed over the core TCP transport layer.
* **Autonomous Garbage Collection:** Automatically detect hard node crashes or network partitions via Gossip protocol timeouts, forcefully transitioning orphaned "Ghost Workloads" to a safe failure state.
* **Asynchronous State Mutations:** Process hundreds of state transitions concurrently via a reactive, lock-free Pub/Sub event bus that never blocks the underlying Raft consensus engine.

## Architecture Modules
* `aegis-consensus`: Raft-based distributed state machine.
* `aegis-discovery`: Gossip protocol for eventually-consistent peer topology and hardware telemetry.
* `aegis-network`: Multiplexed TCP transport layer with Virtual IPC streaming.
* `aegis-fs`: Distributed file system for immutable artifact storage.
* `aegis-runtime`: The physical OS process supervisor and scheduling daemon.

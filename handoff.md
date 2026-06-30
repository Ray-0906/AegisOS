# AegisOS - Developer Handoff Document

Welcome to AegisOS! This document is designed to get any new developer or agent up to speed on the project's current state, architecture, strict invariants, and future roadmap as of the **v2.0.0** milestone.

## Project Overview

AegisOS is a decentralized compute and storage runtime written in Java 21+. It operates as a true "single system image" where a network of independent physical nodes forms a cohesive operating system layer. Instead of relying on a centralized control plane (like Kubernetes), AegisOS nodes operate collectively using Raft consensus and Gossip protocols.

You can submit an executable artifact (currently standard `.jar` binaries) to *any* node, and the cluster will dynamically schedule it based on available physical resources, execute it via a native OS process, and stream its Standard I/O back across the distributed network.

## Current State: Reaching v2.0.0

AegisOS has rapidly evolved from a state-machine experiment into a fully functional, self-healing runtime and distributed system.

### Key Capabilities Currently Implemented:
* **Reactive State Machine**: Fully asynchronous, lock-free Pub/Sub event bus that never blocks the underlying Raft consensus engine.
* **Hardware-Aware Load Balancing**: Nodes measure physical JVM boundaries (`maxMemory`) and CPU cores, broadcasting capacity via Gossip. The scheduler actively prevents OS-level Out-Of-Memory (OOM) crashes by rejecting oversized workloads.
* **Cryptographic Identity**: Workloads are securely mapped to stable 32-byte cryptographic hashes (`NodeId`) derived from Ed25519 public keys, preventing split-brain scheduling collisions.
* **Distributed Stream Multiplexing**: Live-streaming of `stdout` and `stderr` from remote physical processes over the `aegis-network` Virtual IPC overlay.
* **Autonomous Garbage Collection (The Ghost Reaper)**: The `TopologyReconciler` daemon actively detects node crashes via Gossip timeouts and automatically transitions orphaned "Ghost Workloads" to a `FAILED` state.
* **Physical Process Supervision**: The `LocalRuntimeEngine` spawns physical OS processes and tracks exit codes natively using a Java 9+ asynchronous reaper hook.

### Recent "Path B" Milestones Completed:
* **Phase 7: Service Mesh (DNS Late-Binding)**: Implemented `serviceName` mapping so processes can address each other by abstract names instead of strict Process IDs, routing IPC data dynamically at runtime across the cluster.
* **Phase 8: Fortress (mTLS encryption)**: Deployed zero-config auto-generation of 2048-bit RSA X.509 certificates via BouncyCastle. Gossip and orchestration traffic is now fully cloaked in AES-256 TLS 1.3 encryption. Validation defers to the Ed25519 application-layer handshake using a custom permissive TrustManager.
* **Phase 9: The Vault (Distributed File System)**: `AegisFS` provides fault-tolerant chunking and replication. Verified via chaos testing that the system survives the immediate destruction of an ingestion node, utilizing the `AntiEntropyManager` to self-heal and retrieve files from surviving peers via the `/v1/files/*` REST endpoints.
* **Phase 10: The Panopticon (Distributed Observability)**: Implemented distributed tracing by minting `Trace ID`s at the HTTP edge, propagating them via Protobuf `TraceContextProto` into the Raft log, and injecting trace tags (`[TRACE: <id>]`) natively into the Java compute engine's execution telemetry.

## Architecture Breakdown

AegisOS is built out of highly decoupled Maven modules:

* `aegis-api`: Core interfaces and Data Transfer Objects (DTOs) for the runtime. Kept minimal and immutable.
* `aegis-consensus`: The Raft-based distributed state machine. It is the absolute authority on metadata and cluster state.
* `aegis-discovery`: The Gossip protocol implementation managing eventually-consistent peer topology and hardware telemetry (`NodeResources`).
* `aegis-network`: The underlying transport layer. Multiplexes standard RPC traffic alongside the 64KB chunked Virtual IPC stream.
* `aegis-fs`: The distributed file system responsible for storing and replicating immutable artifact binaries.
* `aegis-runtime`: The physical execution layer. Contains the `SimpleProcessScheduler`, `LocalRuntimeEngine`, `InMemoryProcessTable`, and the `TopologyReconciler`.
* `aegis-node`: The glue module that wires all subsystems together and bootstraps the `AegisNode` daemon. Includes Javalin REST APIs.
* `aegis-cli`: The command-line interface for users to interact with the cluster.
* `aegis-client`: Client SDK for connecting to the REST APIs.

## Development Rules & Invariants (CRITICAL)

Before touching the codebase, you MUST understand the architectural invariants. **Read `AGENTS.md` and the `user_rules` completely.** 

A few critical highlights to remember:
1. **Control Loops vs Network Timeouts**: Control loops (like the `TopologyReconciler`) must be decoupled from underlying network timeouts. Polling loops run rapidly (e.g., every 5s) to guarantee responsiveness *after* the network recognizes a failure.
2. **One Owner per Decision**: Every execution decision must have exactly one owner. Only the active Raft Leader can act as the cluster's garbage collector.
3. **No Masquerading (INV-055, INV-056)**: Resources must not masquerade as other resources. Logs != Files. Large binary resources (like logs) must be streamed over IPC, not embedded inside JSON or Protobuf contracts.
4. **Investigation Protocol**: Never run a "staircase" (multiple runs) for evidence gathering. If something fails, perform static analysis first. Your next step is exactly *one* instrumented run. You only run multiple times if you are explicitly hunting timing jitter (<10% flakes) with a falsifiable hypothesis.
5. **REST API Contracts (INV-054, INV-058, INV-064)**: Every REST endpoint must replace an existing CLI capability (no speculative endpoints). Handlers are resource boundaries and must never invoke other handlers. `/v1` is a product contract and cannot be broken.

## Onboarding

To get started with local development and testing:
1. Read the `README.md` and `docs/architecture/internal_workings.md`.
2. Review the invariants in `AGENTS.md`.
3. Compile the system with `mvn clean package -DskipTests` before executing local binaries.
4. Boot a local 3-node cluster and deploy workloads utilizing the REST API boundaries.

Good luck! The foundation is rock solid; it is time to build the future of distributed computing.

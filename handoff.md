# AegisOS - Developer Handoff Document

Welcome to AegisOS! This document is designed to get any new developer or agent up to speed on the project's current state, architecture, strict invariants, and future roadmap as of the **v1.0.0** release.

## Project Overview

AegisOS is a decentralized compute and storage runtime written in Java 21+. It operates as a true "single system image" where a network of independent physical nodes forms a cohesive operating system layer. Instead of relying on a centralized control plane (like Kubernetes), AegisOS nodes operate collectively using Raft consensus and Gossip protocols.

You can submit an executable artifact (currently standard `.jar` binaries) to *any* node, and the cluster will dynamically schedule it based on available physical resources, execute it via a native OS process, and stream its Standard I/O back across the distributed network.

## Current State: v1.0.0

The v1.0.0 milestone transitioned AegisOS from a consensus/state-machine experiment into a fully functional, self-healing runtime. 

### Key Capabilities Currently Implemented:
* **Reactive State Machine**: Replaced synchronous RPC polling with a fully asynchronous, lock-free Pub/Sub event bus that never blocks the underlying Raft consensus engine.
* **Hardware-Aware Load Balancing**: Nodes measure physical JVM boundaries (`maxMemory`) and CPU cores, broadcasting capacity via Gossip. The scheduler actively prevents OS-level Out-Of-Memory (OOM) crashes by rejecting oversized workloads.
* **Cryptographic Identity**: Workloads are securely mapped to stable 32-byte cryptographic hashes (`NodeId`) derived from Ed25519 public keys, preventing split-brain scheduling collisions.
* **Distributed Stream Multiplexing**: Live-streaming of `stdout` and `stderr` from remote physical processes over the `aegis-network` Virtual IPC overlay.
* **Autonomous Garbage Collection (The Ghost Reaper)**: The `TopologyReconciler` daemon actively detects node crashes via Gossip timeouts and automatically transitions orphaned "Ghost Workloads" to a `FAILED` state.
* **Physical Process Supervision**: The `LocalRuntimeEngine` spawns physical OS processes and tracks exit codes natively using a Java 9+ asynchronous reaper hook.

## Architecture Breakdown

AegisOS is built out of highly decoupled Maven modules:

* `aegis-api`: Core interfaces and Data Transfer Objects (DTOs) for the runtime. Kept minimal and immutable.
* `aegis-consensus`: The Raft-based distributed state machine. It is the absolute authority on metadata and cluster state.
* `aegis-discovery`: The Gossip protocol implementation managing eventually-consistent peer topology and hardware telemetry (`NodeResources`).
* `aegis-network`: The underlying transport layer. Multiplexes standard RPC traffic alongside the 64KB chunked Virtual IPC stream.
* `aegis-fs`: The distributed file system responsible for storing and replicating immutable artifact binaries.
* `aegis-runtime`: The physical execution layer. Contains the `SimpleProcessScheduler`, `LocalRuntimeEngine`, `InMemoryProcessTable`, and the `TopologyReconciler`.
* `aegis-node`: The glue module that wires all subsystems together and bootstraps the `AegisNode` daemon.
* `aegis-cli`: The command-line interface for users to interact with the cluster.

## Development Rules & Invariants (CRITICAL)

Before touching the codebase, you MUST understand the architectural invariants. **Read `AGENTS.md` and the `user_rules` completely.** 

A few critical highlights to remember:
1. **Control Loops vs Network Timeouts**: Control loops (like the `TopologyReconciler`) must be decoupled from underlying network timeouts. Polling loops run rapidly (e.g., every 5s) to guarantee responsiveness *after* the network recognizes a failure.
2. **One Owner per Decision**: Every execution decision must have exactly one owner. Only the active Raft Leader can act as the cluster's garbage collector.
3. **No Masquerading**: Resources must not masquerade as other resources. Logs != Files. Large binary resources (like logs) must be streamed over IPC, not embedded inside JSON or Protobuf contracts.
4. **Investigation Protocol**: Never run a "staircase" (multiple runs) for evidence gathering. If something fails, perform static analysis first. Your next step is exactly *one* instrumented run. You only run multiple times if you are explicitly hunting timing jitter (<10% flakes) with a falsifiable hypothesis.

## Next Steps & Roadmap (v2.0)

With the core distributed execution engine solidified, the focus shifts to **Path B / v2.0**:

1. **Visual Control Plane**: Building out a comprehensive UI/dashboard to visually monitor cluster topography, artifact storage, live physical resources, and distributed job statuses.
2. **Agentic Workflows**: Introducing autonomous agents that can natively deploy, monitor, and scale workloads across the AegisOS fabric.
3. **REST APIs**: Upgrading the communication boundary. Currently, external interaction is purely CLI-based. We need to introduce formal REST APIs (following `INV-054` - every REST endpoint must replace an existing CLI capability).
4. **Enhanced Isolation**: Exploring containerized execution (`RUNTIME_CONTAINER`) alongside the existing JVM process execution model.

## Onboarding

To get started with local development and testing:
1. Read the `README.md` and `docs/architecture/internal_workings.md`.
2. Follow `getting_started.md` to bootstrap a local 3-node cluster and deploy your first workload.
3. Compile the system with `mvn clean install -DskipTests` before executing local binaries.

Good luck! The foundation is rock solid; it is time to build the future of distributed computing.

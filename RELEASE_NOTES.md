# AegisOS Release Notes

## v0.2-rc1 — Distributed Artifact Runtime

This release introduces dynamic artifact distribution and execution, transforming the cluster from a static network into a lightweight compute platform.

### New Features
* **Artifact Registry**: Raft-replicated registry tracking JAR metadata (SHA-256, size) ensuring all nodes maintain a consistent view of available jobs.
* **AegisFS Artifact Storage**: CLI uploads route JARs directly into AegisFS, leveraging the existing chunking, encryption, and replication (factor 3) features for secure distribution.
* **Artifact Cache**: Worker nodes locally cache downloaded JARs. Features atomic file system operations and `ConcurrentHashMap` isolation to ensure flawless cache hits and no race conditions under high concurrent stress.
* **ArtifactClassLoader**: ClassLoader isolation guarantees that multiple jobs containing identically-named packages/classes will not collide.
* **Artifact Migration**: Worker failure during a job triggers dynamic recovery. A new node seamlessly downloads the artifact from AegisFS, spins up a new isolated ClassLoader, restores the checkpoint, and resumes execution seamlessly.

### Fixes
* Fixed TOCTOU (Time-Of-Check to Time-Of-Use) race condition in `ArtifactCache` file locking and renaming during concurrent job execution.

---

## v0.1.0 — Core Distributed Infrastructure

The foundational release of AegisOS, establishing the backbone of a distributed peer-to-peer system.

### Core Features
* **Raft Consensus**: Leader election, term validation, log replication, and the `ClusterStateMachine` for maintaining distributed cluster state (control plane).
* **Distributed Storage (AegisFS)**: A highly-available clustered file system supporting chunking, AES-256-GCM encryption, DHT chunk placement, and transparent multi-node replication.
* **Gossip Protocol**: Distributed node discovery and self-healing membership tracking to monitor cluster health and node failures.
* **Distributed Scheduler**: Load-aware scheduler using Raft to assign jobs to nodes and a local `ProcessManager` for execution.
* **Migration Coordinator**: Automatic monitoring of job placement. When a worker dies, jobs are detected, safely unassigned, and rescheduled seamlessly.

# AegisOS

AegisOS is a decentralized compute and storage runtime written in Java. It combines distributed execution, consensus-backed metadata, and autonomous failure recovery into a single, cohesive operating system layer.

Unlike traditional orchestrators that rely on central control planes, AegisOS nodes operate collectively. You can submit an artifact to any node, and the cluster will dynamically schedule it, execute it, and preserve its state—even as nodes join, fail, or leave the network.

## Core Capabilities
* **Leaderless Submission:** Interact with any node in the cluster.
* **Hardware-Aware Scheduling:** Nodes autonomously report CPU and RAM capacity.
* **Cryptographic Routing:** Workloads are tied to verified, stable cryptographic identities.
* **Distributed Standard I/O:** Stream live process logs across the network seamlessly.
* **Self-Healing Topology:** Automatic detection and garbage collection of orphaned processes.

## Architecture
AegisOS is built on a highly modular architecture:
* `aegis-consensus`: Raft-based distributed state machine.
* `aegis-discovery`: Gossip protocol for eventually-consistent peer topology.
* `aegis-network`: Multiplexed TCP transport layer with Virtual IPC streaming.
* `aegis-fs`: Distributed file system for immutable artifact storage.
* `aegis-runtime`: The physical OS process supervisor and scheduling daemon.

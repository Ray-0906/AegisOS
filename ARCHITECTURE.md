# AegisOS Architecture: What Exists?

## What is AegisOS?

AegisOS is a secure, peer-to-peer, distributed operating system runtime written in Java 21. It abstracts a cluster of commodity machines into a single unified computing and storage substrate. Instead of manually deploying JARs to servers or building heavyweight containers, developers upload raw bytecode artifacts directly into the cluster. AegisOS autonomously handles artifact replication, dynamic scheduling, distributed class-loading, and failure recovery.

The system is built on strong decentralization principles: there is no external database or centralized control plane. The nodes cooperatively manage state using Raft consensus and discover each other via a Kademlia-inspired gossip protocol.

---

## High-Level Component Diagram

```mermaid
flowchart TD
    Client[CLI / API Client] -->|Submit Job| Leader[Raft Leader / Scheduler]
    Leader -->|Replicate Metadata| Raft[Raft Consensus Group]
    Leader -->|Assign Job| Worker[Worker Node]
    
    subgraph Worker Node
        Runtime[Job Runtime] --> ClassLoader[Artifact ClassLoader]
        ClassLoader --> Cache[Artifact Cache]
        Cache --> AegisFS[AegisFS Local Storage]
        AegisFS -.->|Fetch Missing| Peer[Peer Nodes]
    end
```

---

## Component Dependency Graph

```mermaid
flowchart BT
    subgraph "aegis-core"
        Proto[Protobuf Messages]
        Crypto[Cryptography]
        Identity[Node Identity]
    end

    subgraph "aegis-network"
        Net[NetworkLayer]
        Handshake[HandshakeHandler]
    end

    subgraph "aegis-discovery"
        Gossip[Kademlia Gossip]
        Membership[MembershipList]
    end

    subgraph "aegis-consensus"
        Raft[RaftNode]
        SM[ClusterStateMachine]
    end

    subgraph "aegis-fs"
        FS[AegisFS]
        Repair[SelfHealingReaper]
        Audit[AntiEntropyManager]
    end

    subgraph "aegis-runtime"
        JobReg[JobRegistry]
        ArtReg[ArtifactRegistry]
        Cache[ArtifactCache]
        PRA[ProcessRuntimeAgent]
        Workspace[WorkspaceInfo]
        Supervisor[ProcessSupervisor]
    end

    subgraph "aegis-scheduler"
        Sched[Scheduler]
        Allocator[ResourceAllocator]
    end

    subgraph "aegis-api"
        PM[ProcessManager]
        API[AegisAPI]
    end

    subgraph "aegis-node"
        Node[AegisNode]
        Config[NodeConfig]
    end

    Net --> Proto
    Net --> Crypto
    Gossip --> Net
    Raft --> Net
    SM --> Proto
    FS --> SM
    FS --> Net
    JobReg --> SM
    ArtReg --> SM
    Cache --> FS
    PRA --> Cache
    PRA --> JobReg
    PRA --> Workspace
    PRA --> Supervisor
    Sched --> JobReg
    Sched --> Allocator
    PM --> Raft
    PM --> FS
    PM --> ArtReg
    Node --> PM
    Node --> Sched
    Node --> PRA
    Node --> Config
```

---

## Artifact Flow

Storage in AegisOS (AegisFS) is designed to be immutable, chunked, and fiercely protective of data integrity.

```mermaid
flowchart TD
    A[Client] -->|"uploadArtifact(name, bytes)"| B[ProcessManager]
    B -->|"write /artifacts/sha256"| C[AegisFS]
    B -->|"propose REGISTER_ARTIFACT"| D[Raft Consensus]
    D -->|commit| E[ClusterStateMachine]
    E -->|"applyRegister()"| F[ArtifactRegistry]

    G[Job Submit] -->|"ArtifactReference in JobSpec"| H[Scheduler]
    H -->|"ASSIGN_JOB"| I[ProcessRuntimeAgent]
    I -->|"resolve(sha256)"| J[ArtifactCache]
    J -->|cache miss| C
    J -->|"SHA-256 verify"| K["Local Disk (.jar + .meta)"]
    K -->|"symlink / copy"| L["Workspace /artifacts/"]
    L --> M[WorkerMain / Job]
```

---

## Workspace Directory Layout

```text
/var/aegisos/workspaces/
└── <job-id>/
    └── exec-<execution-id>/
        ├── artifacts/          ← Mounted artifact files
        │   ├── model.bin       ← symlink → cache/<sha256>.jar
        │   └── config.json     ← symlink → cache/<sha256>.jar
        ├── scratch/            ← Job-private temporary storage
        ├── checkpoints/        ← Local checkpoint staging
        ├── stdout.log          ← Captured standard output
        └── stderr.log          ← Captured standard error
```

Key design decisions:
- The workspace is scoped to **execution ID**, not job ID. Retries and failovers receive fresh scratch space.
- Artifacts are **symlinked** from the cache to prevent duplication.
- Logs are uploaded to AegisFS upon job completion or failure for distributed persistence.

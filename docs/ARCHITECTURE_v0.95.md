# AegisOS v0.95 Architecture Diagrams

---

## Table of Contents
1. [Job Lifecycle State Machine](#1-job-lifecycle)
2. [Artifact Flow](#2-artifact-flow)
3. [Execution Sequence Diagram](#3-execution-path-detail)
4. [Checkpoint & Recovery Flow](#4-checkpoint--recovery-flow)
5. [Workspace Directory Structure](#5-workspace-directory-structure)
6. [Artifact Cache Internals](#6-artifact-cache-internals)
7. [Intelligent Scheduling (New in v0.95)](#7-intelligent-scheduling-new-in-v095)
8. [Component Dependency Graph](#8-component-dependency-graph)
9. [Version History Timeline](#9-version-history-timeline)

---

## 1. Job Lifecycle

```mermaid
stateDiagram-v2
    [*] --> QUEUED : submit()
    QUEUED --> RUNNING : Scheduler assigns to node
    QUEUED --> RESTORING : Has checkpoint, downloading state
    RESTORING --> RUNNING : State restored, resuming
    RUNNING --> COMPLETED : Job returns result
    RUNNING --> FAILED : Job throws exception
    RUNNING --> LOST : Executor node dies (lease expires)
    LOST --> QUEUED : Re-enqueue for recovery
    RUNNING --> CANCELLED : User cancels
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
```

---

## 2. Artifact Flow

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

## 3. Execution Path (Detail)

```mermaid
sequenceDiagram
    participant Client
    participant Leader as Raft Leader
    participant Scheduler
    participant Worker as Executor Node
    participant Cache as ArtifactCache
    participant FS as AegisFS

    Client->>Leader: submit(job, artifacts)
    Leader->>Leader: Commit SUBMIT_JOB to Raft log
    Scheduler->>Scheduler: Detect unassigned job
    Scheduler->>Worker: Probe (resource check)
    Worker-->>Scheduler: Score response
    Scheduler->>Leader: Commit ASSIGN_JOB to Raft log

    Worker->>Worker: Observe ASSIGN_JOB commit
    Worker->>Worker: Provision WorkspaceInfo
    Worker->>Cache: resolve(artifact_sha256)
    alt Cache Hit
        Cache-->>Worker: Local path (pinned)
    else Cache Miss
        Cache->>FS: read(/artifacts/sha256)
        FS-->>Cache: bytes (verified SHA-256)
        Cache-->>Worker: Local path (pinned)
    end
    Worker->>Worker: Mount artifact into workspace
    Worker->>Worker: Launch ProcessSupervisor
    Worker->>Worker: Execute job

    alt Job Completes
        Worker->>Leader: UPDATE_JOB state=COMPLETED
        Worker->>Worker: Upload stdout/stderr to AegisFS
        Worker->>Cache: unpin(artifact)
        Worker->>Worker: Schedule workspace cleanup
    else Job Fails
        Worker->>Leader: UPDATE_JOB state=FAILED
        Worker->>Cache: unpin(artifact)
        Worker->>Worker: Schedule workspace cleanup
    end
```

---

## 4. Checkpoint + Recovery Flow

```mermaid
sequenceDiagram
    participant Job
    participant Agent as ProcessRuntimeAgent
    participant FS as AegisFS
    participant Raft as Raft Consensus
    participant Registry as JobRegistry

    Note over Job: Running on Node A

    Job->>Agent: ctx.checkpoint()
    Agent->>Job: captureState() → byte[]
    Agent->>FS: write(/checkpoints/jobId/seqN)
    Agent->>Raft: Propose UPDATE_JOB_CHECKPOINT
    Raft->>Registry: Apply (fenced by executionId)

    Note over Job: Node A crashes

    Registry->>Registry: Lease expires → job LOST
    Registry->>Registry: Re-enqueue → job QUEUED

    Note over Job: Scheduler assigns to Node B

    Agent->>Registry: Get latest checkpoint
    Agent->>FS: read(/checkpoints/jobId/seqN)
    Agent->>Job: restoreState(byte[])
    Note over Job: Resumes from checkpoint on Node B
```

---

## 5. Workspace Directory Structure

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
- Workspace is scoped to **execution ID**, not job ID — retries get fresh scratch space
- Artifacts are **symlinked** from cache (copy fallback on Windows)
- Logs are uploaded to AegisFS on completion for persistence

---

## 6. Artifact Cache Internals

```mermaid
flowchart TD
    subgraph "ArtifactCache"
        A["resolve(sha256, fsPath)"] --> B{Cached?}
        B -->|"Yes: size+mtime match"| C[Return local path]
        B -->|No| D["Download from AegisFS"]
        D --> E["SHA-256 verify"]
        E -->|Mismatch| F[SecurityException]
        E -->|Match| G["reserveSpace(size)"]
        G --> H{Space available?}
        H -->|Yes| I["Write .jar + .meta"]
        H -->|No| J["LRU eviction (skip pinned)"]
        J --> H
        I --> C
    end

    subgraph "Pin/Unpin"
        K["pin(sha256)"] --> L["pinCount++"]
        M["unpin(sha256)"] --> N["pinCount--"]
        L -.->|"Prevents eviction"| J
    end
```

---

## 7. Intelligent Scheduling (New in v0.95)

```mermaid
sequenceDiagram
    participant S as Scheduler
    participant L as ResourceAllocator
    participant N1 as Node 1 (Has Artifact)
    participant N2 as Node 2 (Clean)
    
    S->>S: Job Submitted (needs artifact)
    S->>N1: ProbeRequest
    S->>N2: ProbeRequest
    
    N1->>N1: Check Cache (HIT)
    N1->>L: tryReserve() -> Success
    N1-->>S: ProbeResult(cpu, mem, bytesSaved=1024MB)
    
    N2->>N2: Check Cache (MISS)
    N2->>L: tryReserve() -> Success
    N2-->>S: ProbeResult(cpu, mem, bytesSaved=0MB)
    
    S->>S: Score N1 (wins due to bytesSaved)
    S->>S: Score N2 (loses)
    
    S->>L: cancelReserve(N2)
    S->>N1: AssignJob
```

---

## 8. Component Dependency Graph

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

## 9. Version History Timeline

```mermaid
timeline
    title AegisOS Release Trajectory
    v0.7 Reliable Execution : Raft cluster formation : Process supervision : Job failure detection
    v0.8 Checkpointing : Worker state checkpoints : Crash-only recovery : Local-only scratch recovery
    v0.9 Workspaces & Artifacts : Immutable ArtifactRegistry : AegisFS integration : Workspace mounting : Per-node artifact cache
    v0.95 Intelligent Scheduling : Scatter-Gather probing : Locality awareness (bytesSaved) : Hotspot rejection
    v1.0 Container Runtime (Planned) : OCI image support : Network isolation : Resource enforcement (cgroups)
```

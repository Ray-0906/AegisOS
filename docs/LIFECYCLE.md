# AegisOS Lifecycle: How Does it Evolve?

This document outlines the temporal behavior of AegisOS, including the lifecycle of individual nodes as they join the cluster, and the execution lifecycle of submitted jobs.

---

## 1. Node Lifecycle

The Node Lifecycle dictates how a newly started instance boots up, discovers its peers, joins consensus, and ultimately becomes available to accept read/write workloads.

```mermaid
stateDiagram-v2
    [*] --> OFFLINE

    OFFLINE --> STARTING : node.start()
    
    STARTING --> DISCOVERING : Gossip initialization
    
    DISCOVERING --> CLUSTER_MEMBER : Membership established
    
    CLUSTER_MEMBER --> READY : API initialized
    
    READY --> OFFLINE : Graceful shutdown or crash
```

### Node Capabilities

As the node traverses the state machine, it gains capabilities. These capabilities are strictly exposed through semantic queries, allowing external clients or internal test harnesses to wait for the exact necessary contract.

*   `isReady()`: Indicates the node has started and its local API is initialized. It can serve read-only queries that do not require consensus.
*   `isWriteReady()`: Indicates that `isReady()` is true AND a Raft Leader has been successfully elected. The node is now capable of accepting write workloads (e.g. `uploadArtifact`, `submitJob`), which internally require proposing to the `ClusterStateMachine`.

---

## 2. Job Lifecycle

The Job Lifecycle defines how a submitted execution task flows through the system.

```mermaid
stateDiagram-v2
    [*] --> QUEUED : submit()
    QUEUED --> RUNNING : Scheduler assigns to node
    
    RUNNING --> LOST : Executor node dies (lease expires)
    RUNNING --> FAILED : Job throws exception
    RUNNING --> COMPLETED : Job returns result
    RUNNING --> CANCELLED : User cancels
    
    LOST --> QUEUED : Re-enqueue for recovery
    
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
```

### 3. Checkpoint & Recovery Flow

When a node running an active job crashes, the system autonomously recovers the job on a different healthy node using the latest saved checkpoint.

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
    Registry->>Registry: Re-enqueue → job QUEUED (executionId++)

    Note over Job: Scheduler assigns to Node B

    Agent->>Registry: Get latest checkpoint
    Agent->>FS: read(/checkpoints/jobId/seqN)
    Agent->>Job: restoreState(byte[])
    Note over Job: Resumes from checkpoint on Node B
```

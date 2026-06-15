# AegisOS Architecture Ownership

This document establishes the strict separation of concerns within the AegisOS node architecture.

## Ownership Table

| Component            | Responsibility        | Must NOT do             |
| -------------------- | --------------------- | ----------------------- |
| RaftNode             | consensus             | execute jobs            |
| ConsensusModule      | network coordination  | mutate runtime          |
| Scheduler            | job placement          | execute jobs directly   |
| JobRegistry          | state machine projection | own persistence      |
| ProcessRuntimeAgent  | orchestration         | spawn runtimes directly |
| RuntimeBackend       | execution abstraction | mutate consensus        |
| JvmRuntimeBackend    | JVM execution         | talk to Docker          |
| DockerRuntimeBackend | container execution   | talk to JVM             |
| MetricsRegistry      | metrics aggregation   | store business state    |
| MetricsServer        | HTTP endpoints        | mutate any state        |
| TimelineRegistry     | timeline aggregation  | drive decisions         |

## Allowed Dependency Flow

```text
Scheduler
    ↓
JobRegistry
    ↓
ProcessRuntimeAgent
    ↓
RuntimeBackend
    ↓
JvmRuntimeBackend / DockerRuntimeBackend
```

## Forbidden Dependencies

| From                   | To                | Violation                              |
| ---------------------- | ----------------- | -------------------------------------- |
| `DockerRuntimeBackend` | `ConsensusModule` | Backend must not talk to Raft          |
| `JvmRuntimeBackend`    | `Scheduler`       | Backend must not know about scheduling |
| `ProcessRuntimeAgent`  | Container IDs     | Orchestrator must not leak backend IDs |
| `MetricsServer`        | Any mutation      | Metrics layer is read-only forever     |
| `RaftNode`             | `TimelineRegistry`| Consensus must not write timelines     |
| `RuntimeBackend`       | `JobRegistry`     | Backends must not mutate job state     |

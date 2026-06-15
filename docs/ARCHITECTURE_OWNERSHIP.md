# AegisOS Architecture Ownership

This document establishes the strict separation of concerns within the AegisOS node architecture.

| Component            | Responsibility        | Must NOT do             |
| -------------------- | --------------------- | ----------------------- |
| RaftNode             | consensus             | execute jobs            |
| ConsensusModule      | network coordination  | mutate runtime          |
| ProcessRuntimeAgent  | orchestration         | spawn runtimes directly |
| RuntimeBackend       | execution abstraction | mutate consensus        |
| JvmRuntimeBackend    | JVM execution         | talk to Docker          |
| DockerRuntimeBackend | container execution   | talk to JVM             |
| MetricsRegistry      | metrics aggregation   | store business state    |
| TimelineRegistry     | timeline aggregation  | drive decisions         |

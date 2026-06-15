# ADR-022: Runtime Ownership Architecture

## Status
Accepted

## Context
With the introduction of containerized workloads running alongside JVM-based workloads, the execution boundary in AegisOS has expanded. Without strict isolation, there is a risk of tight coupling between consensus (Raft), runtime abstraction, and actual process execution, which would lead to unmaintainable code when adding future features (e.g. CRIU snapshotting, secure sandboxing).

## Decision
We establish a strict tiered ownership model for execution, enforcing a one-way flow of dependencies from the orchestrator down to specific runtime backends.

### Ownership Model

1. **`ProcessRuntimeAgent`** 
   - **Responsibility:** Orchestration only. It manages the worker state machine, interacts with Raft via checkpoints, performs leader transitions, and updates the `TimelineRegistry`.
   - **Forbidden:** Must NOT spawn JVMs directly, must NOT execute `docker run` directly.

2. **`RuntimeBackend`**
   - **Responsibility:** Abstraction only. It hides the implementation details of how a workload physically runs.
   - **Forbidden:** Must NOT mutate consensus state, must NOT talk directly to Raft. 

3. **`JvmRuntimeBackend`**
   - **Responsibility:** JVM execution owner. Manages local java `JobExecutor` threads/processes.
   - **Forbidden:** Must NOT talk to Docker or interact with container constructs.

4. **`DockerRuntimeBackend`**
   - **Responsibility:** Container execution owner. Wraps `DockerContainerEngine` to pull images, map directories, and supervise containers.
   - **Forbidden:** Must NOT talk to the JVM executor.

## Invariants
- **State transitions:** Only the orchestrator (`ProcessRuntimeAgent`) may perform state transitions (`RUNNING`, `COMPLETED`, `FAILED`). Backends only report raw exit codes or crashes.
- **Restartability:** Containers are restartable, not resumable. Upon failure, a container execution starts from a clean slate rather than a warm JVM state.
- **Raft abstraction:** No runtime backend is allowed to append to the Raft log or directly query the `ConsensusModule`. All distributed state is mediated by the orchestrator.

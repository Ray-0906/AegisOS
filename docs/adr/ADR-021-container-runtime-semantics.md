# ADR-021: Container Runtime Semantics

## Status
Accepted

## Context
AegisOS v1.1 introduces a dual-runtime architecture, supporting both traditional `AegisJob` JVM processes and external container execution. Container execution has inherently different state management capabilities compared to JVM jobs (which use serialization for state capture). We must explicitly define the limits of container lifecycle semantics to avoid complexity creep and maintain a predictable distributed execution model.

## Decision
We establish explicit guarantees for container execution in v1.1. Crucially, **containers are restartable but not resumable.** True state resumption (via CRIU) and persistent runtime attachment across full cluster restarts are deferred to future releases.

## v1.1 Container Guarantees

| Scenario | Behavior |
| --- | --- |
| Container exits 0 | `COMPLETED` |
| Container exits non-zero | `FAILED` |
| Container cancelled | `CANCELLED` |
| Worker node dies | LOST → requeue → fresh `docker run` |
| Worker partitioned | LOST → migrate → fresh `docker run` |
| Cluster restart while container running | LOST → requeue → fresh `docker run` |
| Resume previous container | **NOT SUPPORTED** |
| CRIU checkpointing | **NOT SUPPORTED** |

## Consequences
- The system correctly cleans up orphaned resources and re-dispatches lost work using restart semantics.
- Relying entirely on fresh `docker run` on node or cluster failure keeps the v1.1 runtime abstraction simple and robust.
- Users deploying container jobs in v1.1 must expect that jobs will run from the beginning if interrupted.
- Testing for cluster restart will be named `ContainerRequeueAfterClusterRestartTest` to explicitly remove the overloaded word "recovery" from v1.1 container terminology.

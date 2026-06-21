# AegisOS Logging Policy (v1.2)

To maintain a healthy, actionable logging environment across the cluster, all new code MUST adhere to the following logging level policies. Avoid leaking temporary telemetry (H7-H15 diagnostics, thread dumps, queue depths) into production logs. 

## INFO
Used for high-level operational lifecycle events. These logs are visible by default and must be low volume.
- Startup and shutdown events
- Leader elected
- Node joined or left the cluster
- Job submitted
- Job completed
- Job failed

## WARN
Used for actionable degradation that does not immediately crash the system but requires attention.
- Lease expiry
- Ownership fencing
- Retrying publication
- Replication unavailable or severely degraded

## ERROR
Used for unrecoverable failures and data loss events.
- Unrecoverable data corruption
- Process crashes or fatal system faults

## DEBUG
Used for tracing system state and workflows, hidden by default.
- Assignment flow (`JOB_ASSIGNED`, `JOB_STARTED`)
- Checkpoint flow (`CHECKPOINT_CREATED`, `CHECKPOINT_RESTORED`)
- Scheduler decisions
- Execution state transitions
- Consensus events (`RAFT_DIAG`)

## TRACE
Used for high-volume, granular internals.
- Heartbeat internals
- Election internals (`RAFT_TASK_ENQUEUED`, `RAFT_TASK_STARTED`)
- Executor queue dumps and thread dumps
- Cleanup and file deletion diagnostics

### Gating
For extremely high volume loops (e.g. `RAFT_DIAG`), wrap `DEBUG` logs behind an explicit system property gate to avoid string interpolation overhead even when DEBUG is disabled:
```java
private static final boolean DIAG = Boolean.getBoolean("aegis.diag");
if (DIAG && log.isDebugEnabled()) {
    log.debug("RAFT_DIAG ...");
}
```

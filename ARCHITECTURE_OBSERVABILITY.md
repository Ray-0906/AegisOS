# Architecture Observability

This document defines the observability guidelines and metrics naming standards for AegisOS v1.2+. Correct observability is crucial; misleading observability creates ghosts.

## Metric Naming

Every metric name must answer **what it measures**. If you cannot determine the unit from the name, the metric is invalid.

### Allowed Suffixes

You must always append explicit suffixes:
```
_MS
_TOTAL
_COUNT
_BYTES
_SECONDS
_PERCENT
```

### Examples

**Good**:
```text
BOOT_DURATION_MS
LEADER_STEPDOWNS_TOTAL
PREVOTES_STARTED_TOTAL
REPLICATION_FAILURES_TOTAL
```

**Bad** (Ambiguous):
```text
BOOT
LEADER_ELECTED
PREVOTE
DISCOVERED
COUNT
```

## Telemetry Lifecycle

**Temporary H7-H15 investigation telemetry**:
```text
INSTRUMENT:
RAFT_DIAG
THREAD_DUMP
```
These must **never** survive a reliability freeze unless explicitly promoted into permanent, gated metrics.

### Golden Rule
**Never add temporary investigation telemetry directly into production code.**
Always use `log.debug(...)` behind an explicit system property flag instead of `System.out.println(...)`.

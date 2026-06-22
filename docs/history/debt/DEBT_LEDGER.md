# AegisOS Debt Ledger

## DEBT-005: Runtime failover test determinism
Runtime failover latency is currently governed by global lease timing (egis.lease.duration.ms). Tests require local overrides to observe recovery within deterministic bounds.
Investigate a deterministic TestClock in Sprint 2 instead of relying on local timing overrides, to ensure we are testing production failover semantics safely.

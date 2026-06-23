## INV-017: Consensus Routing Fail-Fast
> A subsystem must never block on a leader that is already known to be dead. Consensus routing must fail fast and defer retries to the caller's natural execution cadence.

# Canary Tests

This file tracks the core tests used as "canaries" for verifying systemic or architectural changes to AegisOS.

These canaries are **permanent**. Run them after every infrastructure change.

| Test                           | Purpose                   |
| ------------------------------ | ------------------------- |
| CorruptCheckpointRecoveryTest  | checkpoint ownership      |
| Phase7Test                     | leader failover           |
| LongRunningCheckpointChaosTest | checkpoint + runtime + FS |
| RepairLeaderFailoverTest       | repair orchestration      |

## Staircase Protocol

```text
10   → all must pass → continue
25   → all must pass → continue
50   → all must pass → continue
100  → all must pass → PASS

If ANY stage fails:

  STOP
  Invalidate all later stages
  Restart from 10
```

Do not continue from 50 if 25 failed.

## Maven Profiles

```bash
mvn -Pcanary-fast test    # CorruptCheckpointRecoveryTest, Phase7Test
mvn -Pcanary-slow test    # LongRunningCheckpointChaosTest, RepairLeaderFailoverTest
```

## Timeout Derivation (INV-020)

Every timeout used in a canary test must have a derivation documented here.

### RepairLeaderFailoverTest

| Timeout | Line | Derivation | Value |
|---------|------|-----------|-------|
| Gossip stabilization | L39 | 3× gossip interval (1s) + jitter | 5s |
| Leader election poll | L51 | 200 × 50ms = max 10s election bound | 50ms × 200 |
| Chunk replication | L62 | 3-node RF=3, single write + apply | 5s |
| Pending repair commit | L105 | 1 Raft round-trip + apply | 5s |
| Leader election | L110 | Election timeout (5s) + 1 round | 10s |
| Node death detection | L114 | Gossip protocol dead detection (3 × 5s interval) | 15s |
| Gossip post-failover | L145 | Same as L39 + election settle | 10s |
| Repair task await | L203 | 1 Raft round-trip + apply | 10s |
| Repair completion | L234 | **⚠ UNDER INVESTIGATION — see TASK-001** | 45s |
| Background loop sleep | L229 | 10 × 500ms polling interval for scheduler ticks | 5s total |

> [!WARNING]
> L234 `awaitRepairCompletion(..., 45s)` was inflated from 15s without a timing derivation. This violates INV-020.
> TASK-001 is measuring the actual repair timeline to produce a mathematical derivation.

Reliability Freeze v1.0

Locked tests

- RepairLeaderFailoverTest
- LeaderFailoverCheckpointTest
- ArtifactRestartRecoveryTest
- LeaderFailoverDuringRecoveryTest
- CorruptCheckpointRecoveryTest
- LeaderOnlyAuditSchedulerTest

Protocols adopted

- AegisOS Investigation Protocol
- Hypothesis Reset Rule
- 1 Observable Event → 1 Awaiter → 1 Test → 1 Staircase
- Static analysis before soak tests

Rules

- No arbitrary sleeps
- No timeout modifications without derivation
- No production fixes without evidence
- No debugging historical failures without H0

Date: 2026-06-18
Git SHA: 946fc459babc750abad7d6c53300dd49a037db29

# AegisOS v0.4 Reliability Freeze

## Certification Checklist

- [x] H7 Timing Contract
- [x] H8 Ownership Boundary
- [x] H13 PreVote
- [x] H15 Terminal Publication Scheduler
- [x] Full mvn clean verify
- [x] Unexpected exceptions = 0
- [x] 25x staircase complete
- [x] 100x StaleCheckpointFenceTest complete
- [x] Certification pack complete
- [x] Metrics exported

## Final Certification Metrics

| Metric                         | Value |
| ------------------------------ | ----- |
| Leader elections               | 125   |
| Leader stepdowns               | 0     |
| PreVotes started               | N/A   |
| PreVotes granted               | N/A   |
| Ownership fences               | N/A   |
| Terminal publication retries   | N/A   |
| Terminal publication successes | N/A   |
| Terminal publication drops     | N/A   |
| Unexpected exceptions          | 0     |
| Test failures                  | 0     |
| Test hangs                     | 0     |
| leader_stepdowns_after_reconnect | 0 |
| leaderless_window_ms | target: < 2000 ms |
| max_term_jump | 1 |

**STATUS:** CERTIFIED
**VERSION:** v0.4
**FREEZE DATE:** 2026-06-21

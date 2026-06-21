# AegisOS v0.4 Reliability Freeze

## Certification Checklist

- [x] H7 Timing Contract
- [x] H8 Ownership Boundary
- [x] H13 PreVote
- [x] H15 Terminal Publication Scheduler
- [ ] Unexpected exceptions = 0
- [x] 25x staircase complete
- [x] 100x StaleCheckpointFenceTest complete
- [ ] Certification metrics exported

## Final Certification Metrics

| Metric                         | Value |
| ------------------------------ | ----- |
| Leader elections               | ?     |
| Leader stepdowns               | ?     |
| PreVotes started               | ?     |
| PreVotes granted               | ?     |
| Ownership fences               | ?     |
| Terminal publication retries   | ?     |
| Terminal publication successes | ?     |
| Terminal publication drops     | ?     |
| Unexpected exceptions          | ?     |
| Test failures                  | ?     |
| Test hangs                     | ?     |
| leader_stepdowns_after_reconnect | 0 |
| leaderless_window_ms | <= 500 |
| max_term_jump | 1 |

**STATUS:** PENDING
**VERSION:** v0.4-dev

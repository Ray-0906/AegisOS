# AegisOS Investigation Protocol (Mandatory)

Before any soak test:
1. Define the observed symptom.
2. Define the last known successful event.
3. Draw the remaining pipeline.
4. List all remaining components.
5. Create exactly one hypothesis.

Escalation rules:
* Static analysis first.
* 1 instrumented run.
* 10 runs if timing jitter suspected.
* 25 runs if frequency <10%.
* 100 runs only if a falsifiable hypothesis exists.
* Never run 300 iterations.

Prohibited:
* "Run N times and see what happens."
* Introducing production fixes before capturing evidence.
* Combining multiple hypotheses into one experiment.
* Adding retries to execution threads.
* Modifying timeouts before deriving timing contracts.

Every experiment must answer exactly one question.

## AegisOS Investigation Protocol v2
Never run a staircase for evidence gathering.
Staircases exist only for verification.

Evidence gathering order:
0. Static analysis
1. 1 instrumented run
2. 10 runs (timing jitter)
3. 25 runs (rare <10% flakes)
4. 100 runs (only after a falsifiable hypothesis exists)

185-run staircases are verification only.
If production code is untouched: 1 -> 10 -> stop
If production code changed: 10 -> 25 -> 50 -> 100

Before creating a hypothesis:
Find the first event that violates expectations.
Do not hypothesize about earlier components.
Work backwards only one event at a time.

## Hypothesis Reset Rule
After every structural fix:
STOP.
Reset all active hypotheses.
Do not continue investigating a historical failure signature until you have proven it still exists on the current HEAD.

Rules:
1. Historical failures are not evidence.
2. Passing tests are evidence.
3. Any code change invalidates downstream assumptions.
4. Every structural fix starts a new investigation tree.
5. Before opening a new hypothesis, define H0: "Does the original failure still reproduce on the current HEAD?"

If H0 is falsified, close the investigation.
Do not debug ghosts.

---

# Architecture Invariants

**INV-024**
Removing a sleep must expose the hidden contract the sleep was satisfying. Derive the dependency the sleep was masking.

**INV-025**
Every runnable execution attempt must be uniquely identified by `(jobId, executionId)`. Once executionId=N is superseded, executionId<N+1 must NEVER: start, resume, checkpoint, commit state, emit COMPLETED, emit FAILED.

**INV-029**
Workload reality is authoritative. Metadata publication is eventually consistent. RUNNING must never exist before a workload exists.

**INV-030**
ProcessRuntimeAgent is the sole authority on workload existence. JobRegistry is the sole authority on job lifecycle visibility.

**INV-031**
Unknown is a first-class state. Timeout != failure. Missing acknowledgement != rejection.

**INV-032**
Every execution decision must have one owner.
- Packet acceptance: ProcessRuntimeAgent
- Workload existence: ProcessRuntimeAgent
- Lifecycle visibility: JobRegistry
- Recovery: JobSupervisor

**INV-036**
Execution completion and metadata durability are independent responsibilities.

**INV-037**
Workers must never become schedulers. A worker should never retry, sleep, poll, or scan after its workload has finished.

**ENV-001**
Compiler, IDE, filesystem, and build cache corruption are environment failures, not test outcomes. Discard the run entirely.

**REF-002**
Do not replace an existing invariant with a weaker one unless you can prove the stronger invariant is unnecessary.

**REF-003**
100/100 reliability is not sufficient justification for refactoring.

**REF-004**
Do not create framework primitives preemptively. One test != one awaiter.

**REF-005**
A completed refactor wave is immutable. New refactors require an explicit trigger.

**REL-001**
Do not automatically commit after successful verification. The user decides when a checkpoint becomes a commit.

---

# Observability Rules

**OBS-001**
Every observability feature must answer a real operational question. Good: "Who is leader?" "Why is this job stuck?" "Is the cluster healthy?"

**OBS-002**
Observability must be read-only. Commands may inspect, aggregate, and report, but never mutate.

**OBS-004**
Commands have ownership. One operational question -> one canonical command.

**OBS-005**
Metrics must be owned by the subsystem that creates the event. MetricsSnapshot may aggregate but never derive historical events retroactively.

**OBS-006**
MetricsSnapshot must explicitly distinguish gauges from counters.

**OBS-007**
Observability metrics describe AegisOS behavior, not host machine behavior.

**OBS-008**
Transports consume exporters. Exporters consume snapshots. Snapshots consume subsystem state.

**OBS-009**
Never add temporary investigation telemetry directly into production code. Use `log.debug(...)` behind a system property flag instead of `System.out.println(...)`.

**OBS-010**
Every metric name must answer what it measures. Always append explicit suffixes: `_MS`, `_TOTAL`, `_COUNT`, `_BYTES`, `_SECONDS`.

---

# Documentation Rules

**DOC-001**
SCORECARD is the single source of truth for project state. Only SCORECARD may define ACTIVE, FROZEN, LOCKED, COMPLETED. Other documents may reference but never redefine them.

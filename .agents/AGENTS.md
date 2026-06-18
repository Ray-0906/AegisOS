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

AegisOS Investigation Protocol v2

Never run a staircase for evidence gathering.

Staircases exist only for verification.

Evidence gathering order:

0. Static analysis
1. 1 instrumented run
2. 10 runs (timing jitter)
3. 25 runs (rare <10% flakes)
4. 100 runs (only after a falsifiable hypothesis exists)

185-run staircases are verification only.

If production code is untouched:
    1 -> 10 -> stop

If production code changed:
    10 -> 25 -> 50 -> 100

Every experiment must answer one question.

Before creating a hypothesis:

Find the first event that violates expectations.

Do not hypothesize about earlier components.

Work backwards only one event at a time.

# AGENTS.md Addition: Hypothesis Reset Rule

## Investigation Invariant

After every structural fix:

STOP.

Reset all active hypotheses.

Do not continue investigating a historical failure signature until you have proven it still exists on the current HEAD.

Rules:

1. Historical failures are not evidence.
2. Passing tests are evidence.
3. Any code change invalidates downstream assumptions.
4. Every structural fix starts a new investigation tree.
5. Before opening a new hypothesis, define H0:

   "Does the original failure still reproduce on the current HEAD?"

Escalation:

* Static analysis first.
* 1 instrumented run.
* 10 runs if timing jitter is suspected.
* 25 runs if frequency <10%.
* 100 runs only to verify H0 or a single falsifiable hypothesis.

If H0 is falsified, close the investigation.

Do not debug ghosts.

# Wave 1 Refactoring Rules

1. **Never wait for a stronger system invariant than the test actually needs.**
2. **Passing tests are evidence; historical failures are not evidence.**
3. **Wait for readiness, not implementation details.**

## Refactor staircase rule:

If code semantics are unchanged and only synchronization primitives changed:

1 -> 10 -> 25 -> stop

100 only if:
- new awaiter is introduced AND
- a historical flake existed OR
- a production invariant changed

## System Invariants and Constraints

INV-024
Removing a sleep must expose the hidden contract the sleep was satisfying.
Do not replace sleeps mechanically.
Derive the dependency the sleep was masking.

ENV-001
Compiler, IDE, filesystem, and build cache corruption
are environment failures, not test outcomes.
Discard the run entirely.

REF-002
Do not replace an existing invariant with a weaker one unless
you can prove the stronger invariant is unnecessary.

REF-003
100/100 reliability is not sufficient justification for refactoring.
A refactor requires at least one of:
- arbitrary sleep
- hidden contract
- subsystem leakage
- duplicated synchronization logic
- overly strong invariant proven unnecessary

Otherwise, close the work item.

REF-004
Do not create framework primitives preemptively.
A new awaiter requires evidence of reuse or necessity.
One test != one awaiter.

# AegisOS Operations: How do I reason about AegisOS when something goes wrong?

This manual codifies the official protocols for investigating bugs, mitigating failures, and preventing framework regressions in AegisOS. Following these rules ensures predictability and protects against debugging ghosts.

---

## 1. System Invariants

These invariants define foundational guarantees and operational constraints.

*   **INV-024**: Removing a sleep must expose the hidden contract the sleep was satisfying. Do not replace sleeps mechanically. Derive the dependency the sleep was masking.

*(More invariants to be added as discovered)*

---

## 2. Investigation Protocol v2 (Mandatory)

Before initiating any soak test or making structural changes, you must systematically capture the failure scope.

1.  **Define the observed symptom.**
2.  **Define the last known successful event.**
3.  **Draw the remaining pipeline.**
4.  **List all remaining components.**
5.  **Create exactly one hypothesis.**

### Rules of Engagement
*   **Work backwards one event at a time**: Find the first event that violates expectations. Do not hypothesize about earlier components.
*   **Never run a staircase for evidence gathering**: Staircases exist *only* for verification.
*   **Prohibited actions**:
    *   "Run N times and see what happens."
    *   Introducing production fixes before capturing evidence.
    *   Combining multiple hypotheses into one experiment.
    *   Adding retries to execution threads.
    *   Modifying timeouts before deriving timing contracts.

### Hypothesis Reset Rule
After every structural fix, **STOP**. Reset all active hypotheses. Do not continue investigating a historical failure signature until you have proven it still exists on the current HEAD.
**H0:** *"Does the original failure still reproduce on the current HEAD?"*

If H0 is falsified, close the investigation. Do not debug ghosts.

---

## 3. Reliability Verification Rules

Use these specific run counts when gathering evidence or verifying structural fixes.

### Evidence Gathering
*   **Static analysis** first.
*   **1 instrumented run** to capture the failure stack or observe execution flow.
*   **10 runs** if timing jitter is suspected.
*   **25 runs** if the failure frequency is rare (<10%).
*   **100 runs** *only* after a falsifiable hypothesis exists.

### Verification Staircase
When verifying a fix, escalate only when the previous level justifies it.
*   If production code is **untouched** (e.g. test refactoring): `1 -> 10 -> stop`
*   If production code **changed**: `10 -> 25 -> 50 -> 100`

---

## 4. Refactor Rules

The following rules guard against premature abstraction and over-refactoring.

*   **REF-002**: Do not replace an existing invariant with a weaker one unless you can prove the stronger invariant is unnecessary.
*   **REF-003**: 100/100 reliability is not sufficient justification for refactoring. A refactor requires at least one of:
    *   arbitrary sleep
    *   hidden contract
    *   subsystem leakage
    *   duplicated synchronization logic
    *   overly strong invariant proven unnecessary
*   **REF-004**: Do not create framework primitives preemptively. A new awaiter requires evidence of reuse or necessity. One test ≠ one awaiter.
*   **REF-005**: A completed refactor wave is immutable. Do not re-open a wave to "look for more opportunities." New refactors require an explicit trigger (e.g., historical flake, production bug, repeated code pattern).

---

## 5. Release & Environment Rules

*   **REL-001**: Do not automatically commit after successful verification. A successful staircase proves correctness. The user decides when a checkpoint becomes a commit.
*   **ENV-001**: Compiler, IDE, filesystem, and build cache corruption are environment failures, not test outcomes. Discard the run entirely.

---

## 6. Observability Rules

*   **OBS-001**: Every observability feature must answer a real operational question (e.g. "Who is leader?", "Why is this job stuck?").
*   **OBS-002**: Observability must be read-only. Observability features must never alter system state. Commands may inspect, aggregate, and report, but never mutate.
*   **OBS-004**: Commands have ownership. One operational question -> one canonical command. Avoid aliases that represent different domains.
*   ### OBS-005: Event Ownership
Metrics must be owned by the subsystem that creates the event.
`MetricsSnapshot` may aggregate metrics.
`MetricsSnapshot` may never derive historical events retroactively.

### OBS-006: Metric Types
`MetricsSnapshot` must explicitly distinguish gauges from counters.
A gauge represents current state.
A counter represents accumulated events.
Never expose counters as gauges.

### OBS-007: Domain Scope
Observability metrics describe AegisOS behavior, not host machine behavior.
JVM/OS metrics are infrastructure metrics.

### OBS-008: Transport Boundaries
Transports may not traverse subsystems.
Transports consume exporters.
Exporters consume snapshots.
Snapshots consume subsystem state.

# AegisOS v0.4 Roadmap: The Correctness Release

This document outlines the v0.4 plan to transition AegisOS from "functionally complete" to "provably correct under churn." The highest priority is safety, observability, and auditability.

## Core Architectural Invariants

1. **Raft metadata is authoritative. Observed state is evidence.** (ADR-016)
2. **Audit Report ≠ Repair Recommendation.** We explicitly separate auditing, repair recommendation, and repair execution to prevent destructive actions from transient issues.

## Sprint Plan

### Sprint 1: Membership & Foundations
* **Close ADR-013 (Client Mode vs. Node Mode):** Establish strict, accurate membership. If membership is wrong, node counts, replica counts, and audits are wrong.
* **Define Verification Contracts:** Refine ADR-017 to explicitly define who initiates verification, what constitutes evidence (object-specific), and what threshold allows repair.

### Sprint 2: Storage-First Auditing
* **Implement the Audit Layer:** Detect and report divergence between Raft metadata and physical reality, starting with Storage (AegisFS) due to observed metadata growth issues (`MetadataReplicas=53` vs `LiveReplicas=3`).
* **Periodic & Triggered Scans:**
  - Periodic audit: `60s` intervals.
  - Membership-triggered audit: `Immediate` (when a node joins, leaves, or is declared DEAD).

### Sprint 3: Safe Reconciliation & Repair
* **Execute Repairs based on Verified Audits:** Ensure repairs only occur when evidence meets strict, object-specific thresholds.
  - *Storage:* Two consecutive scans confirming divergence.
  - *Jobs:* Node DEAD + heartbeat expired + job absent + two scans.
* **Mitigate False-Positive Reconciliation:** Prevent the cluster from committing repairs based on flawed audit data, which is currently the single largest risk for cluster-wide corruption.

### Sprint 4: Snapshots & Log Compaction
* **Raft Snapshots:** Implement state machine snapshotting and log truncation to prevent unbounded memory/disk usage on long-running clusters.
* *Note: Can be swapped with Sprint 5 depending on operational pressure.*

### Sprint 5: Observability & Expansion
* **Metrics & Telemetry:** Expose real-time data (`cluster_jobs_running`, replication lag, scheduler queue latency, recovery latency) to monitor the correctness of the new audit and repair mechanisms.
* **Full Reconciliation Expansion:** Extend the audit and repair loops to cover all subsystems.

---

## Out of Scope for v0.4
- Containers / Kubernetes integration
- Multi-tenancy & strict user isolation
- Fair scheduling / GPU affinities

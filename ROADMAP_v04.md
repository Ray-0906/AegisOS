# AegisOS v0.4 Roadmap: The Correctness Release

This document outlines the v0.4 plan to transition AegisOS from "functionally complete" to "provably correct under churn." The highest priority is safety, observability, and auditability.

## Core Architectural Invariants

1. **Raft metadata is authoritative. Observed state is evidence.** (ADR-016)
2. **Audit Report ≠ Repair Recommendation.** We explicitly separate auditing, repair recommendation, and repair execution to prevent destructive actions from transient issues.

## Sprint Plan

### Sprint 1: CLI Isolation & Membership Visibility ✅ COMPLETE
* **ADR-013 (Client Mode vs. Node Mode):** CLI no longer joins as a transient Gossip member. Uses strict Client-Server boundary.
* **ADR-018 (Client Transport Strategy):** Exposed `GET /membership` endpoint for visibility.

### Sprint 2: Storage-First Auditing ✅ COMPLETE
* **ADR-016 (Source of Truth Policy):** Raft Log → `ClusterStateMachine` → `FileIndex` is authoritative.
* **Audit Layer:** `ChunkMetadataInventory` → `ObservedStateCollector` → `DivergenceReportGenerator`.
* **Periodic Scans:** `60s` intervals via `StorageAuditScheduler`.
* **Rule:** Audit strictly observes. Zero repair or mutation logic.

### Sprint 3: Raft Membership Correctness ✅ COMPLETE
* **Raft decoupled from Gossip:** `votingPeers` from `ClusterConfiguration.voters()` (Raft-replicated), NOT Gossip.
* **Bootstrap/Join split:** Genesis `ADD_VOTER(self)` at log index 1.
* **Dynamic electability:** `BooleanSupplier isVotingMember` for voter promotion without restart.
* **8 safety tests:** PartitionSafety, QuorumIsolation, SelfRemoval, VoterPromotion, etc.

### Sprint 4: Verification + Recommendation Pipeline ✅ COMPLETE
* **ADR-017 (Verification Contract):** Two consecutive audit scans + membership validation + physical observation.
* **StorageVerifier:** Validates divergences via persistence, liveness, and re-observation.
* **Target-free recommendations:** `RepairRecommendation` carries evidence, no placement decisions.
* **Leader-only semantics:** Audit cycles run exclusively on the active consensus leader.

### Sprint 5: Two-Phase Repair Execution ✅ COMPLETE
* **ADR-019 (Repair Execution Contract):** Two-phase repair guaranteeing metadata == reality at every committed transition.
* **Phase A:** `REPAIR_CHUNK` creates PENDING task (no metadata mutation).
* **Phase B:** `REPAIR_COMPLETE` applies `ADD_REPLICA` to FileIndex after physical copy succeeds.
* **RepairProposer:** Freshness guard, pre-proposal re-verification, one-repair-per-chunk-in-flight.
* **RepairTaskStore:** Raft-replicated PENDING/COMPLETE/EXPIRED lifecycle.
* **Legacy cleanup:** `SelfHealingReaper.java` deleted.
* **3 acceptance tests:** RepairExecutionSignOff, RepairCopyFailure, RepairLeaderFailover.

### Sprint 6: Snapshots & Log Compaction ← NEXT
* **Raft Snapshots:** Implement state machine snapshotting and log truncation to prevent unbounded memory/disk usage.
* **Snapshot Transfer:** Allow lagging nodes to catch up via snapshot install.

---

## Out of Scope for v0.4
- Containers / Kubernetes integration
- Multi-tenancy & strict user isolation
- Fair scheduling / GPU affinities


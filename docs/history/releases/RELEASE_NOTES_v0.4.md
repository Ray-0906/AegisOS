# AegisOS v0.4 Release Notes — Correctness & Observability

This release focuses on transitioning AegisOS from "functionally complete" to "provably correct under churn," enforcing strict invariants, decoupling consensus from liveness discovery protocols, and establishing a robust non-mutating auditing and verification framework.

---

## Major Features & Architectural Sprints

### 1. CLI Isolation & Membership Visibility (Sprint 1)
* **Client Boundary (ADR-018):** Transient CLI commands and client queries no longer join Gossip as ephemeral members. All client requests go through client-specific message handshakes, guaranteeing cluster membership stability.
* **Membership Endpoint:** Exposed `GET /membership` REST endpoint to view Gossip-level membership and active node telemetry.

### 2. Measurement-Only Storage Auditing (Sprint 2)
* **Authoritative Source of Truth (ADR-016):** Replays the Raft Log to materialize the `FileIndex` view.
* **Audit Pipeline:** Implements a read-only audit loop (`ChunkMetadataInventory` -> `ObservedStateCollector` -> `DivergenceReportGenerator`) comparing expected chunk placement against physically observed local/remote storage to output chunk replication discrepancies (`GET /audit/storage`).

### 3. Log-Authoritative Raft Membership Correctness (Sprint 3)
* **Decoupled Quorum:** Quorum boundaries now query `ClusterConfiguration.voters()` (log-replicated consensus state) instead of Gossip discovery.
* **Bootstrap/Join Split:**
  - `--bootstrap` mode appends a genesis `ADD_VOTER(self)` entry at index 1 and self-elects.
  - Default (join mode) starts with empty voters, behaves as non-electable, and waits for a leader to propose voter promotion.
* **Leader Safety Guards:** Validates reachability, lag threshold, voter duplication, and single-flight limits before committing voter promotions.
* **Leader Self-Removal & Step-Down:** Hardened step-down state transitions so a leader can safely propose its own voter removal, step down, and trigger reelection without exceptions.

### 4. Verification + Recommendation Pipeline (Sprint 4)
* **Verification Contracts (ADR-017):** Prohibits mutations from raw audits. Audit reports are *evidence*; cluster state is *authority*.
* **StorageVerifier Engine:** Verifies chunk divergences through a strict pipeline:
  1. **Persistence Check:** Requires the divergence to persist across at least 2 consecutive scans.
  2. **Membership Check:** Fails verification (`NODE_UNAVAILABLE`) if any missing replica nodes are not Gossip-`ALIVE`.
  3. **Re-observation:** Re-observes the network and storage at verification time against the frozen snapshot, returning `NO_LONGER_DIVERGENT` or `OBSERVATION_MISMATCH` if healed or changed.
* **Leader-Only Semantics:** The `StorageAuditScheduler` runs scans and generates recommendation streams strictly on the consensus leader. Followers bypass cycles and clear recommendations.
* **Leader-Local Ephemeral History:** `AuditReportStore` acts as a sliding window of the last 20 scans. It is leader-local ephemeral state, is not replicated, and is discarded on leadership change (separating evidence history from authoritative state).

---

## Verification & Test Suite Results

All 37/37 integration, unit, and chaos tests pass successfully, verifying:
* **`Sprint4SignOffTest`:** Full 4-phase integration verifying healthy cluster audits, first-scan historical validation, second-scan `VERIFIED` recommendation generation (without consensus mutations), and replica restore cleanup.
* **`LeaderOnlyAuditSchedulerTest`:** Proves follower bypass, leader-only audit execution, and new leader catch-up/takeover upon failover.
* **`StorageVerificationTest`:** Validates recommendation stability, consecutive scans sliding-window evidence collections, and status transitions.
* **Raft Membership Resilience:** Safe network partition recovery (`PartitionSafetyTest`), configurations surviving full restarts (`ConfigurationSurvivesRestartTest`), non-voter vote granting (`NonVoterGrantsVoteTest`), and leader self-removal (`SelfRemovalLeaderTest`).

---

## Technical Debt & Out of Scope for v0.4

For details on deferred items, see [TECHNICAL_DEBT.md](file:///c:/Users/astra/Desktop/projects/AgeisOS/TECHNICAL_DEBT.md):
* **Raft Snapshots & Log Compaction:** Scheduled for Sprint 6 to prevent unbounded log recovery times.
* **Under-Replication on DEAD Nodes:** Under-replication caused by dead nodes is intentionally non-actionable (`NODE_UNAVAILABLE`) until manual membership reconfiguration occurs.

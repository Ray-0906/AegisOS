# AegisOS v0.4 — Engineering Handoff

> Handoff for the next engineer. Read this top-to-bottom once before touching code.
> It captures **what exists, what was verified, what is currently planned, and where to start.**

---

## 0. TL;DR

- **What it is:** AegisOS — a secure, peer-to-peer, distributed OS runtime in **Java 21**. Nodes authenticate, gossip membership, store files, and execute JAR artifacts. 
- **Current Phase:** We are in the **v0.4 Correctness and Observability Refactor**. The system has moved from "assumed correctness" to "proven correctness" with strict audit frameworks, membership decoupling, and verification contracts.
- **Milestone Status:** Sprints 1, 2, 3, and 4 are **COMPLETE and SIGNED OFF**. Ready to begin Sprint 5 (Observability Expansion).

---

## 1. Work Completed in v0.4

### Sprint 1: CLI Isolation and Membership Visibility
- **CLI Isolation (ADR-018):** CLI no longer joins as a transient Gossip member. Uses strict Client-Server boundary via `CLIENT_COMMAND` and `CLIENT_QUERY` on the `NetworkLayer`.
- **Visibility:** Exposed `GET /membership` endpoint on `MetricsServer`.
- **Discovery:** Revealed critical flaw: Raft's `votingPeers` was populated from Gossip's `MembershipList`.

### Sprint 2: Storage Audit Framework (Measurement-Only)
- **Source of Truth (ADR-016):** Raft Log → `ClusterStateMachine` → `FileIndex` (materialized view).
- **Audit pipeline:** `ChunkMetadataInventory` → `ObservedStateCollector` → `DivergenceReportGenerator`.
- **Endpoint:** `GET /audit/storage`.
- **Validation:** `StorageAuditRealityTest` detects induced `UNDER_REPLICATED` divergence and clears on restore.
- **Rule:** Audit strictly observes. Zero repair or mutation logic.

### Sprint 3: Raft Membership Correctness ✅ SIGNED OFF
- **Core change:** `votingPeers` now comes from `ClusterConfiguration.voters()` (Raft-replicated), NOT from Gossip. Gossip is now a liveness concern only.
- **Bootstrap/Join split:**
  - `--bootstrap`: Genesis `ADD_VOTER(self)` at log index 1, self-elects.
  - Default (join mode): Empty voters, non-electable, waits for leader promotion.
- **Voter set derived from log:** `ClusterConfiguration` is always reconstructed via state machine replay, never set imperatively.
- **Dynamic electability:** `BooleanSupplier isVotingMember` allows voter promotion without restart.
- **Leader-side safety guards:** Existence/reachability, replication lag, not-already-voter, one-in-flight constraint.
- **Self-removal:** Leader can `REMOVE_VOTER(self)` — steps down, new leader elected.
- **Non-voter vote granting:** `handleRequestVote()` does NOT check `isVotingMember`. Non-voters grant votes per Raft paper. Only `onElectionTimeout()` gates election initiation.

#### Key files
| File | Purpose |
|------|---------|
| [ClusterConfiguration.java](file:///c:/Users/astra/Desktop/projects/AgeisOS/aegis-consensus/src/main/java/com/aegisos/consensus/ClusterConfiguration.java) | Raft-replicated voter/observer set with version counter |
| [ConsensusModule.java](file:///c:/Users/astra/Desktop/projects/AgeisOS/aegis-consensus/src/main/java/com/aegisos/consensus/ConsensusModule.java) | Genesis entry, membership validation, lag threshold |
| [RaftNode.java](file:///c:/Users/astra/Desktop/projects/AgeisOS/aegis-consensus/src/main/java/com/aegisos/consensus/RaftNode.java) | Election gate, vote granting, self-removal fix, replayCommitted() |
| [AegisNode.java](file:///c:/Users/astra/Desktop/projects/AgeisOS/aegis-node/src/main/java/com/aegisos/node/AegisNode.java) | Dynamic votingPeers/isVotingMember suppliers |

#### Test coverage (31 tests, all passing)
| Test | What it proves |
|------|---------------|
| `PartitionSafetyTest` | Network partition: only majority elects leader |
| `RaftQuorumIsolationTest` | Dead voters stay in quorum (Gossip DEAD ≠ voter removal) |
| `SelfRemovalLeaderTest` | Leader removes self, steps down, new leader elected |
| `VoterPromotionTest` | Promoted non-voter starts election after leader death |
| `JoinModeNonElectableTest` | Join-mode node cannot self-elect |
| `AddOfflineVoterRejectedTest` | Cannot promote unreachable node |
| `ConfigurationSurvivesRestartTest` | ADD_VOTER entries survive full cluster restart |
| `NonVoterGrantsVoteTest` | Non-voters grant RequestVote RPCs |

### Sprint 4: Verification + Recommendation Pipeline ✅ SIGNED OFF
- **Verification Engine:** `StorageVerifier` validates divergences via (1) persistence checks (at least 2 consecutive scans), (2) liveness checks on missing nodes, and (3) re-observing physical reality at verification time against a frozen snapshot.
- **Target-Free Recommendations:** `RepairRecommendation` generates target-free, placement-free recommendations containing identical evidence scans.
- **Leader-Only Semantics:** Audit scans and recommendation cycles run exclusively on the active consensus leader. Followers bypass the runs and clear their local state.
- **AuditReportStore:** Leader-local ephemeral in-memory sliding window of the last 20 scans. 
  > [!NOTE]
  > **Leadership Ephemeral State:** `AuditReportStore` is leader-local ephemeral state. It is not replicated and is intentionally discarded on leadership change. This is because **audit history is evidence, whereas cluster state is authority** (very different responsibilities).
- **Test coverage:**
  - `AuditPersistenceTest` (unit): Tests scan persistence, sliding window capacity, and gaps.
  - `StorageVerificationTest` (integration): Tests verification status transitions (`VERIFIED`, `INSUFFICIENT_HISTORY`, `NODE_UNAVAILABLE`, `OBSERVATION_MISMATCH`, `NO_LONGER_DIVERGENT`) and recommendation stability across 5 scans.
  - `LeaderOnlyAuditSchedulerTest` (integration): Tests leader-only semantics and failover audit handoff.
  - `Sprint4SignOffTest` (integration): Tests the complete 4-phase non-mutating pipeline.

---

## 2. Core Architecture Decision Records (ADRs)

Before writing code, understand these locked ADRs in `docs/adr/`:

| ADR | Decision |
|-----|----------|
| **ADR-016** | **Source of Truth Policy.** Raft Log is authoritative. `FileIndex` is the authoritative materialized view. |
| **ADR-017** | **Verification Contract.** Repairs require *two consecutive audit scans* + *membership validation* + *physical observation agreement*. |
| **ADR-018** | **Client Transport Strategy.** Clients (CLI) use `CLIENT_COMMAND`/`CLIENT_QUERY` via `NetworkLayer`. No HTTP port in Gossip. |

---

## 3. Current Project State

| Area | Status |
|------|--------|
| Storage durability | ✅ Proven |
| Corruption detection | ✅ Proven |
| CLI membership isolation | ✅ Proven |
| Membership visibility | ✅ Proven |
| Storage audit framework | ✅ Proven |
| Raft decoupled from Gossip | ✅ **Proven (Sprint 3)** |
| Formal cluster configuration | ✅ **Built (Sprint 3)** |
| **Reconciliation engine** | ✅ **Proven (Sprint 4)** |
| **Snapshots** | **NOT BUILT (Sprint 6)** |

---

## 4. Sprint Roadmap

```text
Sprint 1  ✅  CLI Isolation + Membership Visibility
Sprint 2  ✅  Storage Audit Framework (Measurement-Only)
Sprint 3  ✅  Raft Membership Correctness
Sprint 4  ✅  Verification + Recommendation Pipeline
Sprint 5  ←   Observability Expansion
Sprint 6       Snapshots
```

---

## 5. Where to Start (Sprint 4: Reconciliation Engine)

The biggest remaining correctness risk is now the repair/reconciliation pipeline.

### What Sprint 4 should build

The reconciliation engine operates **on top of** the membership model established in Sprint 3. It should:

1. **Use the audit pipeline from Sprint 2** — `ChunkMetadataInventory` + `ObservedStateCollector` + `DivergenceReportGenerator` provide the input.
2. **Propose repairs via Raft** — any mutation must go through `consensus.propose()`.
3. **Respect ADR-017** — repairs require two consecutive audit scans + membership validation + physical observation agreement before proposal.
4. **Never bypass ClusterConfiguration** — the reconciliation engine must check `ClusterConfiguration.voters()` for membership decisions, not Gossip.

### Critical constraints

- **Do NOT auto-promote nodes.** Voter management is manual (`add-voter` / `remove-voter` CLI commands).
- **Do NOT couple repair logic to Gossip liveness.** Use Gossip only for "is this node reachable?" checks, never for "who is in the cluster?"
- **Scope to `UNDER_REPLICATED` first.** Start with the simplest case (missing replicas), not over-replication or corruption repair.

### Key entry points

- [DivergenceReportGenerator.java](file:///c:/Users/astra/Desktop/projects/AgeisOS/aegis-fs/src/main/java/com/aegisos/fs/DivergenceReportGenerator.java) — produces the divergence report
- [ObservedStateCollector.java](file:///c:/Users/astra/Desktop/projects/AgeisOS/aegis-fs/src/main/java/com/aegisos/fs/ObservedStateCollector.java) — queries physical chunk presence
- [ChunkMetadataInventory.java](file:///c:/Users/astra/Desktop/projects/AgeisOS/aegis-fs/src/main/java/com/aegisos/fs/ChunkMetadataInventory.java) — derives expected state from metadata

### What to read first

1. This handoff (you're reading it)
2. `docs/adr/` — the three locked ADRs
3. `docs/v0.4/raft-membership-design-review.md` — Sprint 3 design rationale
4. The Sprint 3 walkthrough artifact (conversation artifacts directory)
5. `StorageAuditRealityTest.java` — the Sprint 2 proof that the audit pipeline works

---

## 6. Build and Test

```bash
# Full build
mvn clean install

# Run all integration tests (includes chaos marathon, ~7 min)
mvn test -pl aegis-test-cluster

# Run only Sprint 3 safety tests (~40s)
mvn test -pl aegis-test-cluster -Dtest="PartitionSafetyTest,RaftQuorumIsolationTest,SelfRemovalLeaderTest,VoterPromotionTest,ConfigurationSurvivesRestartTest,NonVoterGrantsVoteTest,JoinModeNonElectableTest"

# Run a 3-node cluster locally
java -jar aegis-cli/target/aegis-cli-*.jar start --bootstrap --port 7001 --home node1
java -jar aegis-cli/target/aegis-cli-*.jar start --seed 127.0.0.1:7001 --port 7002 --home node2
java -jar aegis-cli/target/aegis-cli-*.jar start --seed 127.0.0.1:7001 --port 7003 --home node3
```

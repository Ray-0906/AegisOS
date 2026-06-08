# AegisOS v0.4 — Engineering Handoff

> Handoff for the next engineer. Read this top-to-bottom once before touching code.
> It captures **what exists, what was verified, what is currently planned, and where to start.**

---

## 0. TL;DR

- **What it is:** AegisOS — a secure, peer-to-peer, distributed OS runtime in **Java 21**. Nodes authenticate, gossip membership, store files, and execute JAR artifacts. 
- **Current Phase:** We are in the middle of the **v0.4 Correctness and Observability Refactor**. We are shifting the system from "assumed correctness" to "proven correctness" by building strict audit frameworks, removing architectural coupling, and formalizing verification contracts.
- **Milestone Status:** Sprints 1 and 2 are **COMPLETE and VERIFIED**. We are preparing to begin Sprint 3.

---

## 1. Work Completed in v0.4 So Far

### Sprint 1: CLI Isolation and Membership Visibility
- **CLI Isolation (ADR-018):** Previously, CLI commands forced the CLI to join the cluster as a transient Gossip member, polluting `votingPeers` and disrupting consensus. This has been fixed. The CLI now connects via a strict Client-Server boundary using `CLIENT_COMMAND` and `CLIENT_QUERY` on the existing single-transport TCP `NetworkLayer`.
- **Visibility:** Exposed `GET /membership` endpoint on the `MetricsServer` to compare Raft Voters against Gossip Peers.
- **Discovery:** Revealed a critical architectural flaw: Raft's `votingPeers` is currently populated directly from Gossip's `MembershipList`. This means Raft quorum size is an emergent property of Gossip.

### Sprint 2: Storage Audit Framework (Measurement-Only)
- **Source of Truth (ADR-016):** Confirmed the Raft Log is the ultimate authority, and `FileIndex` is its deterministic materialized view.
- **Audit Implementation:** Built a read-only pipeline that extracts expected state (`ChunkMetadataInventory`), compares it against physical reality (`ObservedStateCollector`), and generates a diff (`DivergenceReportGenerator`). 
- **Endpoint:** Exposed `GET /audit/storage` on the `MetricsServer`.
- **Validation:** Wrote `StorageAuditRealityTest` which proves the pipeline correctly detects an induced physical chunk deletion as `UNDER_REPLICATED`, and clears the divergence when the chunk is restored. 
- **Crucial Rule Maintained:** The audit framework *strictly* observes. It contains exactly zero repair or mutation logic.

---

## 2. Core Architecture Decision Records (ADRs)

Before writing code for any reconciliation, you must understand these locked ADRs in `docs/adr/`:

| ADR | Decision |
| --- | --- |
| **ADR-016** | **Source of Truth Policy.** The Raft Log is authoritative. `FileIndex` is the authoritative materialized view. We audit the physical reality against the materialized view. |
| **ADR-018** | **Client Transport Strategy.** Clients (CLI) use the existing `NetworkLayer` via `CLIENT_COMMAND`/`CLIENT_QUERY`. We do NOT add HTTP `api_port` fields to Gossip `Hello` messages just for client convenience. |
| **ADR-017** | **Verification Contract.** **[LOCKED FOR SPRINT 3]** Strictly defines what evidence is required before proposing a repair to Raft. Example: Storage repairs require *two consecutive audit scans* + *membership validation* + *physical observation agreement*. |

---

## 3. Current Project State

| Area | Status |
| --- | --- |
| Storage durability | Proven |
| Corruption detection | Proven |
| CLI membership isolation | Proven |
| Membership visibility | Proven |
| Storage audit framework | Proven |
| **Raft decoupling from Gossip** | **NOT BUILT (High Risk)** |
| **Reconciliation engine** | **NOT BUILT** |
| Formal cluster configuration | Not built |
| Snapshots | Not built |

---

## 4. Where to Start (Sprint 3)

We are currently parked at a major architectural checkpoint. The implementation plan for Sprint 3 has been drafted and submitted to the user for review.

**DO NOT WRITE RECONCILIATION CODE YET.** 

The biggest unsolved correctness risk is the coupling of Raft to Gossip (`votingPeers = discovery.membership().allPeers()`). If we build automated Raft repair proposals on top of a dynamically shrinking/growing quorum, we risk catastrophic split-brain scenarios. 

**Next Steps for You:**
1. Read the drafted `implementation_plan.md` in the workspace root.
2. Await the user's feedback on Sprint 3 Sequencing (whether to fix Raft decoupling in Sprint 3A before building the Reconciliation Engine in Sprint 3B) and Raft Bootstrapping semantics.
3. Execute the approved plan.

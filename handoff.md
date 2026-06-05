# AegisOS v0.2 — Engineering Handoff

> Handoff for the next engineer. Read this top-to-bottom once before touching code.
> It captures **what exists, what was verified, what was NOT verified, and where to start.**

---

## 0. TL;DR

- **What it is:** AegisOS v0.2 — a secure, peer-to-peer, distributed OS runtime in **Java 21**. Nodes authenticate, gossip membership, store files, and dynamically distribute/execute JAR artifacts using isolated ClassLoaders. Jobs can now migrate seamlessly across the cluster without needing the JARs pre-installed on the nodes.
- **Current Milestone:** **Phase 1 (Storage Integrity Verification) is 100% complete and rigorously hardened.** The underlying distributed filesystem (AegisFS) correctly handles silent data corruption, orphaned chunks, node failures, and full cluster restarts perfectly. We are now gated to begin **Phase 2 (Runtime Execution & Artifact Deployment)**.
- **Validation status:** Advanced storage integrity tests (Tests A-O) PASS perfectly. `mvn clean test` PASSES.

---

## 1. Source of truth documents

| File | Purpose |
| --- | --- |
| `DetailAndPlanning.md` | Original full design doc (the "spec"). |
| `RELEASE_NOTES.md` | Feature breakdown for v0.1 and v0.2. |
| `docs/MANUAL_TESTING_V02.md` | Step-by-step instructions for manually testing the cluster in multiple terminals. |
| `docs/SECURITY_MODEL.md` | Identity, trust (TOFU), handshake, channel crypto. |
| `docs/RAFT_NOTES.md` | Raft roles, timing, log persistence, commit. |
| `docs/GOSSIP_NOTES.md` | Membership states, gossip merge, bootstrap. |
| `storage_invariants.md` | **[CRITICAL]** The 7 iron-clad storage invariants governing metadata and replica management. |
| `handoff.md` | This file. |

---

## 2. Tech stack & versions

- Java release: **21** (uses virtual threads heavily).
- Group/version: `com.aegisos:aegisos-parent:0.1.0-SNAPSHOT`
- Protobuf: `3.25.3`.
- Bouncy Castle: `bcprov-jdk18on:1.78.1`.
- SLF4J `2.0.13` + Logback `1.5.6`.
- Picocli `4.7.6` (CLI).
- JUnit Jupiter `5.10.2`, Surefire `3.2.5`.

---

## 3. Module map & layering

11 modules. Strict layering — every cross-node call goes through `aegis-network`.

```
aegis-core (identity, crypto, proto, models)
        └──> aegis-network (TCP, handshake, AES-GCM session, replay guard)
                    └──> aegis-discovery (gossip membership + Kademlia DHT)
                                └──> aegis-consensus (Raft)
                                          ├──> aegis-fs (chunking, encryption, replication)
                                          └──> aegis-scheduler (resource reporting + placement)
                                                      └──> aegis-runtime (artifact registry, cache, classloader, migration)
                                                                  └──> aegis-api (public OS API)
aegis-node  = wires everything into one JVM process
aegis-cli   = `aegis` command, shaded to aegis-cli/target/aegis.jar
aegis-test-cluster = in-process N-node integration + chaos tests
```

---

## 4. Implementation Phases & Status

| Phase | Delivered | Gate / Tests | Status |
| --- | --- | --- | --- |
| **Phase 1: Storage Integrity** | Hardened Raft-backed FileIndex, AntiEntropyManager, ChunkScrubber, SelfHealingReaper. Proved metadata conformity, corruption containment, and full cluster recovery. | `TestAdvancedStorage.java` (Tests A through O) | ✅ **COMPLETE** |
| **Phase 2: Job/Runtime Layer** | Dynamic JAR upload, ArtifactRegistry, ArtifactCache, ClassLoader isolation, job recovery semantics. | Transitioning to verify `Phase7Test` and `Phase8Test`. | 🔄 **UP NEXT** |
| **Phase 3: Stress & Resilience**| Concurrent cache hit/miss resolution, isolation, and race condition fixes | `Phase8Test` (100 concurrent jobs) | ⏳ PENDING |

---

## 5. Key design decisions

1. **Storage Invariants First:** AegisFS enforces 7 strict rules (see `storage_invariants.md`). **Metadata is truth; Disk must conform.**
2. **Serialization = Protocol Buffers** for wire; **Java serialization** for opaque job state/results.
3. **Artifact ID = SHA-256(JAR)**. Ensures deduplication and easy integrity checks.
4. **Reusing AegisFS for Artifacts**. Avoided reinventing chunking/replication for JARs.
5. **ArtifactRegistry over Raft**. Metadata in Raft; raw data in AegisFS.
6. **Isolated ClassLoaders**. Prevents linkage errors between competing artifacts.

---

## 6. How to build & run

```bash
mvn clean install -DskipTests
mvn test
# Run Advanced Storage Integrity Suite:
java -cp "aegis-cli/target/test-classes;aegis-cli/target/aegis.jar" com.aegisos.test.TestAdvancedStorage
```

See `docs/MANUAL_TESTING_V02.md` for cluster start/run instructions.

---

## 7. First things to do (recommended order)

1. **Reconfirm green locally.** Run `TestAdvancedStorage` to prove AegisFS is rock-solid.
2. Review `storage_invariants.md` to understand the foundations of the data plane.
3. Pivot to Phase 2: Open `ArtifactRegistry.java` and `ArtifactCache.java` to begin runtime execution and job recovery semantics.

---

_Last updated for v0.2 transition. Status: Storage Integrity hardened; ready for Job/Runtime Layer execution._

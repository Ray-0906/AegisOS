# AegisOS v0.2 — Engineering Handoff

> Handoff for the next engineer. Read this top-to-bottom once before touching code.
> It captures **what exists, what was verified, what was NOT verified, and where to start.**

---

## 0. TL;DR

- **What it is:** AegisOS v0.2 — a secure, peer-to-peer, distributed OS runtime in **Java 21**. Nodes authenticate, gossip membership, store files, and dynamically distribute/execute JAR artifacts using isolated ClassLoaders. Jobs can now migrate seamlessly across the cluster without needing the JARs pre-installed on the nodes.
- **How far it got:** **v0.2 (Distributed Artifact Runtime) is code-complete and validated.** It introduces the `ArtifactRegistry`, `ArtifactCache`, `ArtifactClassLoader`, and seamless migration of dynamically loaded jobs.
- **Validation status:** Full suite has been executed successfully with `mvn clean test` PASS, including an aggressive 100-job concurrency stress test (`Phase8Test`) and ClassLoader isolation test.

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
| `handoff.md` | This file. |

---

## 2. Tech stack & versions

- Java release: **21** (uses virtual threads heavily).
- Group/version: `com.aegisos:aegisos-parent:0.1.0-SNAPSHOT` (Note: Maven version still SNAPSHOT, but git tag is v0.2.0).
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

### Key v0.2 Module Additions

- **aegis-runtime** — Added `ArtifactRegistry` (Raft-backed control plane for artifacts), `ArtifactCache` (AegisFS-backed data plane caching with atomic concurrent locks), `ArtifactClassLoader` (JAR isolation).
- **aegis-cli** — Added `aegis artifact upload <jar>`. The `run` command now targets uploaded artifacts via `--artifact <sha256>`.

---

## 4. Wire protocol

Generated via `aegis.proto`. 
- **v0.2 Additions**: `CommandType.REGISTER_ARTIFACT`, `ArtifactRecord`, updated `RunJob` to support `artifactId` and `className`.

---

## 5. Implementation Phases (Completed)

| Phase | Delivered | Gate / Tests |
| --- | --- | --- |
| **0 - 6** | Bootstrap, Identity, Discovery, Raft, AegisFS, Scheduler, Migration | `Phase1Test` to `Phase6Test` |
| **7 Artifact Runtime** | Dynamic JAR upload, registry, cache, and execution | `Phase7Test` (integration) |
| **8 Stress & Resilience**| Concurrent cache hit/miss resolution, isolation, and race condition fixes | `Phase8Test` (100 concurrent jobs) |

---

## 6. Key design decisions

1. **Serialization = Protocol Buffers** for wire; **Java serialization** for opaque job state/results.
2. **Artifact ID = SHA-256(JAR)**. Ensures deduplication and easy integrity checks.
3. **Reusing AegisFS for Artifacts**. Avoided reinventing chunking/replication for JARs.
4. **ArtifactRegistry over Raft**. Metadata in Raft; raw data in AegisFS.
5. **Isolated ClassLoaders**. Prevents linkage errors between competing artifacts.
6. **Concurrent Cache Locks**. Thread-safe resolution and atomicity on `.jar` caching using `ConcurrentHashMap`.

---

## 7. Testing status — CURRENT

**Executed and passing**: `mvn clean test` → **PASS**

### Notable tests:
- `Phase7Test`: End-to-end artifact upload, distribution, and execution.
- `Phase8Test`: 100 concurrent artifact jobs stress test (validates cache race conditions and Raft load).
- `Phase9Test`: Chaos integration testing (validates startup race conditions, quorum inflation, and repeated node/leader churn).
- `test_cache.ps1`: Cache hit/miss validation.
- `test_migration.ps1`: Validates node death recovery for dynamically loaded jobs.
- `test_isolation.ps1`: Validates `ArtifactClassLoader` isolation with conflicting class names.

---

## 8. How to build & run

```bash
mvn clean install -DskipTests
mvn test
```

See `docs/MANUAL_TESTING_V02.md` for cluster start/run instructions.

---

## 9. First things to do (recommended order)

1. **Reconfirm green locally.** `mvn clean test`.
2. Review `RELEASE_NOTES.md` and `docs/MANUAL_TESTING_V02.md` to understand the artifact system.

---

## 10. Known risks / things to verify carefully

- **Raft log unbounded growth** — still append-only without compaction/snapshots.
- **Static-ish membership assumption in Raft** — peer supplier is discovery-based but there is no formal joint-consensus membership-change protocol yet.

---

## 11. Suggested next work (v0.3 Post-Green)

- Raft **log compaction / snapshotting**; optional RocksDB backend.
- Raft **membership changes** (joint consensus) — cluster size is effectively static today.
- Real latency maps to re-enable the latency weight in `PlacementAlgorithm`.
- Backpressure / flow control on `PeerConnection`.

---

_Last updated for v0.2. Status: Artifact Runtime complete; **full test suite passing**._

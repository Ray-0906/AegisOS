# AegisOS v0.1 — Engineering Handoff

> Handoff for the next engineer. Read this top-to-bottom once before touching code.
> It captures **what exists, what was verified, what was NOT verified, and where to start.**

---

## 0. TL;DR

- **What it is:** AegisOS v0.1 — a secure, peer-to-peer, distributed OS runtime in **Java 21**, built as an **11-module Maven project**. Nodes are identical and autonomous (no central server): they authenticate each other, gossip membership, agree on shared state via **Raft**, store files redundantly in an encrypted DHT-placed file system, and run/migrate jobs that survive node death.
- **How far it got:** **All 6 planned phases + cross-cutting work are code-complete.** 94 Java files, full protobuf wire schema, 6 phase integration tests + chaos tests + several unit tests.
- **Validation status:** Java and Maven are installed and working (`openjdk 21.0.11`, Maven `3.9.12`). Full suite has been executed successfully with **`mvn -q clean test` PASS** after stabilization fixes documented in §7.

---

## 1. Source of truth documents

| File | Purpose |
| --- | --- |
| `DetailAndPlanning.md` | Original full design doc (the "spec"). Section numbers referenced in code comments map to this. |
| `.cursor/plans/aegisos_v0.1_build_0fa2fa9e.plan.md` | The implementation plan that was executed (phases, gates, chosen defaults). **Do not edit.** |
| `README.md` | Build/run/CLI quickstart. |
| `docs/SECURITY_MODEL.md` | Identity, trust (TOFU), handshake, channel crypto, replay defense. |
| `docs/RAFT_NOTES.md` | Raft roles, timing, log persistence, commit, client forwarding, limits. |
| `docs/GOSSIP_NOTES.md` | Membership states, gossip merge, bootstrap, Kademlia. |
| `handoff.md` | This file. |

---

## 2. Tech stack & versions (from root `pom.xml`)

- Java release: **21** (uses virtual threads heavily).
- Group/version: `com.aegisos:aegisos-parent:0.1.0-SNAPSHOT`.
- Protobuf: `3.25.3` (compiled by `protobuf-maven-plugin` 0.6.1 + `os-maven-plugin` 1.7.1).
- Bouncy Castle: `bcprov-jdk18on:1.78.1` (Ed25519 / X25519 / AES-GCM primitives).
- SLF4J `2.0.13` + Logback `1.5.6`.
- Picocli `4.7.6` (CLI).
- JUnit Jupiter `5.10.2`, Surefire `3.2.5`.

> **Network note:** the first build needs Maven Central access to fetch the platform-specific `protoc` artifact. If you're offline/air-gapped, pre-seed the local `.m2` repo.

---

## 3. Module map & layering

11 modules. Strict layering — **every cross-node call goes through `aegis-network` only**; no raw sockets above it.

```
aegis-core (identity, crypto, proto, models)
        └──> aegis-network (TCP, handshake, AES-GCM session, replay guard)
                    └──> aegis-discovery (gossip membership + Kademlia DHT)
                                └──> aegis-consensus (Raft)
                                          ├──> aegis-fs (chunking, encryption, replication, self-heal)
                                          └──> aegis-scheduler (resource reporting + placement)
                                                      └──> aegis-runtime (job exec, checkpoint, migration)
                                                                  └──> aegis-api (public OS API)
aegis-node  = wires everything into one JVM process (manual DI)
aegis-cli   = `aegis` command (picocli), shaded to aegis-cli/target/aegis.jar
aegis-test-cluster = in-process N-node integration + chaos tests
```

### Module responsibilities

- **aegis-core** — `NodeId` (`SHA-256(pubkey)`), `NodeIdentity`, `IdentityService`, `KeyStore` (`~/.aegis/identity.key`), `TrustStore` (TOFU + whitelist); crypto utils `Ed25519`, `X25519` (ECDH+HKDF), `AesGcm`, `Hashing`, `HexUtil`; `Endpoint`; message types; **`src/main/proto/aegis.proto`** (the entire wire schema).
- **aegis-network** — `NetworkLayer` (top-level transport, request/response RPC), `tcp/TcpServer` + `TcpConnectionPool`, `PeerConnection`, `crypto/HandshakeHandler` + `SessionCipher` + `EstablishedSession`, `wire/Framing` + `EnvelopeCodec` + `ReplayGuard`.
- **aegis-discovery** — `gossip/MembershipList` (ALIVE/SUSPECT/DEAD), `gossip/GossipProtocol`, `dht/RoutingTable` + `dht/KademliaRouter`, `DiscoveryService` (seed bootstrap).
- **aegis-consensus** — `RaftNode`, `RaftLog` (append-only file), `RaftMetadataStore` (term/votedFor), `RaftRole`, `election/ElectionTimer`, `replication/LogReplicator`, `RaftStateMachine` + `ClusterStateMachine`, `RaftTransport`, `ConsensusModule` (wires Raft to network + client-command forwarding), `NotLeaderException`.
- **aegis-fs** — `ChunkSplitter` (1 MB), `ChunkCipher` (per-chunk AES-GCM, cluster-key wrap), `ChunkStore` (content-addressed disk), `ChunkReplicator`, `ChunkPlacement`, `FileIndex` (Raft-replicated metadata), `AegisFS`, `SelfHealingReaper`.
- **aegis-scheduler** — `ResourceReporter`, `NodeResourcesView`, `PlacementAlgorithm` (weighted least-loaded), `Scheduler` (score → probe → assign via Raft).
- **aegis-runtime** — `AegisJob<T>`, `JobContext`, `JobExecutor` (virtual thread + isolated ClassLoader), `JobRegistry`, `ProcessRuntimeAgent`, `Serialization`, `CheckpointManager`, `MigrationCoordinator`.
- **aegis-api** — `AegisOS` (facade), `ProcessManager`, `ClusterInfo`, `NodeInfo`, `JobHandle`.
- **aegis-node** — `AegisNode` (full wiring of all layers, exposes `AegisOS`), `NodeConfig`, `NodeMain` (standalone entry point).
- **aegis-cli** — `AegisCLI` + `commands/` (`Start`, `Info`, `Nodes`, `Put`, `Get`, `Ls`, `Run`, `Status`, `ClientCommands`).
- **aegis-test-cluster** — `ClusterHarness` (spins N in-process nodes), `Phase1Test`..`Phase6Test`, `Phase3ChaosTest`, sample jobs `jobs/PrimeCounter`, `jobs/CheckpointableSum`.

---

## 4. Wire protocol

All messages are protobuf, defined in **`aegis-core/src/main/proto/aegis.proto`**. Key messages:

- Envelope/transport: `MessageHeader` (type, correlation id, handshake fields), `Envelope` (signed), `Hello`, `Verify`.
- Membership: `PeerEntry`, `PeerStatus`, `MembershipList`.
- DHT: `FindNode`, `FindNodeResult`.
- Raft: `RaftLogEntry`, `RequestVote`, `AppendEntries`, `CommandType`, `StateCommand`, `ClientCommandResult`.
- FS: `ChunkRef`, `FileMetadata`, `StoreChunk`, `FetchChunk`.
- Jobs/scheduler: `NodeResources`, `JobSpec`, `JobRecord`, `JobState`, `JobUpdate`, `ProbeRequest`, `ProbeResult`, `RunJob`.
- Test KV: `KvPut`.

`CommandType` (Raft state-machine commands): `REGISTER_FILE`, `PLACE_CHUNK`, `ASSIGN_JOB`, `NODE_JOIN`, `NODE_LEAVE`, `UPDATE_JOB`, `KV_PUT`.

Generated Java lands in `com.aegisos.proto.*` at build time. **If your IDE shows `com.aegisos.proto` as missing, you simply haven't run a build yet** — run `mvn generate-sources` or `mvn package`.

---

## 5. Phase-by-phase: what was implemented & the gate it targets

| Phase | Delivered | "Done when" gate (implemented and verified by tests) |
| --- | --- | --- |
| **0 Bootstrap** | Parent POM, 11 modules, all deps, full `.proto`. | Project builds. |
| **1 Identity + Secure Net** | Ed25519 identity, `NodeId=SHA256(pubkey)`, KeyStore/TrustStore; TCP transport with ECDH(X25519)+HKDF handshake, Ed25519 mutual auth, AES-256-GCM channel (fresh 12B nonce), replay guard (±30s + nonce); `aegis start`/`info`. | Two nodes do signed+encrypted handshake & exchange a message → `Phase1Test`. |
| **2 Discovery** | Gossip (push-pull, K=3, version/timestamp merge), membership ALIVE→SUSPECT(3×)→DEAD(10×), seed bootstrap, Kademlia routing; `aegis nodes`. | 5 nodes converge <15s, offline detected <10s → `Phase2Test`. |
| **3 Raft** | Election (150–300ms randomized, 50ms heartbeat), append-only persisted log, majority commit, persisted term/votedFor, cluster state machine + command dispatch, leader forwarding of client commands. | 3-node elects leader, replicates 1000 entries, recovers <500ms after leader kill → `Phase3Test` + `Phase3ChaosTest`. |
| **4 AegisFS** | 1 MB chunking, per-chunk AES-GCM (cluster-wrapped key), content-addressed store (`chunkId=SHA256(ciphertext)`), RF=3 placement (Kademlia + alive peers, no two replicas same node), metadata in Raft, 60s self-healing reaper; `aegis put/get/ls`. | 10MB file written at A read at B; replica-holder crash re-replicates <60s → `Phase4Test`. |
| **5 Scheduler + Runtime** | `ResourceReporter` (gossips `NodeResources`), weighted least-loaded `PlacementAlgorithm` (cpu .4 / mem .3 / store .1 / jobs .2 in code), `Scheduler` (score→probe→assign via Raft), `AegisJob` runtime on virtual threads w/ isolated ClassLoader, job lifecycle in Raft, job bytecode shipped via AegisFS; public `AegisOS`/`ProcessManager`/`ClusterInfo`; `aegis run/status`. | `PrimeCounter` submitted at A runs on least-loaded B, correct result; 100-job batch → `Phase5Test`. |
| **6 Migration + Checkpoint** | `CheckpointManager` (periodic `captureState()`→serialize→AegisFS→record path in Raft via UPDATE_JOB), `MigrationCoordinator` (detect dead node → reselect → `restoreState()` → update `assignedNodeId`). | 30s+ job killed mid-run on B migrates to C with correct result → `Phase6Test` + kill chaos. |

> Note: the plan's stated job weights were cpu .4/mem .3/store .1/jobs .1/lat .1; the implementation drops the (unpopulated) latency term and uses **cpu .4 / mem .3 / store .1 / jobs .2**. See `PlacementAlgorithm`.

---

## 6. Key design decisions (resolved open questions)

1. **Serialization = Protocol Buffers** for wire; **Java serialization** only for opaque job state/results (`Serialization` util).
2. **Trust = TOFU + manual whitelist** (`TrustStore`). First-seen pubkey is pinned to a `NodeId`; key substitution is rejected.
3. **Chunk size = 1 MB**, per-file configurable.
4. **Raft log = append-only file** in v0.1 (RocksDB is a documented future option). No snapshot/compaction yet → log grows unbounded.
5. **Job bytecode delivery = via AegisFS** (the job class is shipped as a stored file, loaded by an isolated ClassLoader on the executor).
6. **Crash-stop failure model** — Raft assumes non-Byzantine. No BFT.

---

## 7. Testing status — CURRENT

**Executed and passing**:

- Environment:
  - Java: `openjdk 21.0.11`
  - Maven: `3.9.12`
- Full suite:
  - `mvn -q clean test` → **PASS** (latest run)
- Unit tests passing:
  - `aegis-core` → `IdentityServiceTest`
  - `aegis-consensus` → `RaftLogTest`
  - `aegis-fs` → `ChunkSplitterTest`
  - `aegis-scheduler` → `PlacementAlgorithmTest`
  - `aegis-discovery` → `MembershipListTest`
  - `aegis-network` → `ReplayGuardTest`
- Integration/chaos tests passing (`aegis-test-cluster`):
  - `Phase1Test`, `Phase2Test`, `Phase3Test`, `Phase3ChaosTest`, `Phase4Test`, `Phase5Test`, `Phase6Test`

### Root causes found during stabilization

1. **Raft peer selection coupled to unstable liveness view**  
   Consensus peer supplier using only `alivePeerIds()` caused partial cluster views during startup and churn, producing leader instability/timeouts in Phase 3 chaos paths.

2. **Phase 3 tests assumed immediate membership convergence**  
   Tests asserted Raft progress before gossip membership converged, making them timing-sensitive and flaky.

3. **Scheduler/consensus commit timeout too small under 100-job burst**  
   `Phase5Test.schedulesManyJobs` timed out waiting for `ASSIGN_JOB` commit during load.

4. **Transient no-leader window not retried in submit path**  
   `ProcessManager.submit()` could fail on `NotLeaderException: no known leader` during election transitions.

5. **Phase 6 timeout too tight for suite-level contention**  
   `Phase6Test` passed in isolation but could exceed prior await budget in full-suite runs.

### Stabilization fixes applied

- `aegis-node/src/main/java/com/aegisos/node/AegisNode.java`
  - Raft peer supplier now derives from `membership().allPeers()` (excluding self), not only `alivePeerIds()`.
- `aegis-test-cluster/src/test/java/com/aegisos/cluster/Phase3Test.java`
  - Wait for full membership convergence before Raft assertions.
- `aegis-test-cluster/src/test/java/com/aegisos/cluster/Phase3ChaosTest.java`
  - Wait for full membership convergence before leader/progress assertions.
- `aegis-scheduler/src/main/java/com/aegisos/scheduler/Scheduler.java`
  - Increased assignment commit wait (`ASSIGN_TIMEOUT_MS`).
- `aegis-consensus/src/main/java/com/aegisos/consensus/ConsensusModule.java`
  - Increased commit and forwarded client-command request timeouts.
- `aegis-api/src/main/java/com/aegisos/api/ProcessManager.java`
  - Added bounded retry loop for transient `NotLeaderException` during scheduling.
- `aegis-test-cluster/src/test/java/com/aegisos/cluster/Phase6Test.java`
  - Increased await timeout from 60s to 90s.

---

## 8. How to build & run

```bash
# from repo root
mvn -q clean package        # compiles all modules, generates protobuf, shades aegis-cli/target/aegis.jar
mvn -q test                 # runs unit + Phase1..6 cluster tests

# run a node
java -XX:+UseZGC -jar aegis-cli/target/aegis.jar start --port 9000
java -jar aegis-cli/target/aegis.jar start --port 9001 --seed 127.0.0.1:9000
```

CLI surface:

```text
aegis info
aegis start [--port N] [--seed h:p]
aegis nodes  --seed h:p
aegis put <local> <path> --seed h:p
aegis get <path> <local> --seed h:p
aegis ls [path] --seed h:p
aegis run <JobClass> [args...] --seed h:p
aegis status <jobId> --seed h:p
```

Identity is created on first `start` at `~/.aegis/identity.key` (owner-only perms).

---

## 9. First things to do (recommended order)

1. **Reconfirm green locally.** `mvn -q clean test`.
2. **Run targeted regression checks** for the previously flaky paths:
   - `Phase3Test`, `Phase3ChaosTest`, `Phase5Test#schedulesManyJobs`, `Phase6Test`.
3. **If CI is slower than local, keep an eye on timing-sensitive gates** (`Phase5`/`Phase6`) and adjust only test-level wait budgets if needed.
4. **Then move to enhancements** in §11 (Raft compaction/snapshots, membership changes, richer DHT, etc.).

---

## 10. Known risks / things to verify carefully

- **Raft log unbounded growth** — still append-only without compaction/snapshots.
- **Static-ish membership assumption in Raft** — peer supplier is discovery-based but there is no formal joint-consensus membership-change protocol yet.
- **Timeout sensitivity under heavy CI contention** — Phase 5/6 rely on timing windows even after stabilization.
- **Client-command forwarding path is still latency-sensitive** — now more tolerant due to timeout/retry adjustments, but worth watching in larger clusters.
- **`CheckpointManager.runWithCheckpointing`** still accepts an unused `JobExecutor` argument; cleanup candidate.

---

## 11. Suggested next work (post-green)

- Raft **log compaction / snapshotting**; optional RocksDB backend.
- Raft **membership changes** (joint consensus) — cluster size is effectively static today.
- DHT `FIND_VALUE` and real latency maps to re-enable the latency weight in `PlacementAlgorithm`.
- Backpressure / flow control on `PeerConnection`.
- Metrics + structured tracing across RPCs.
- Harden job sandboxing (the isolated ClassLoader is not a security boundary).

---

## 12. File index (where to look)

- Wire schema: `aegis-core/src/main/proto/aegis.proto`
- Crypto: `aegis-core/src/main/java/com/aegisos/core/crypto/`
- Identity: `aegis-core/src/main/java/com/aegisos/core/identity/`
- Transport + handshake: `aegis-network/src/main/java/com/aegisos/network/`
- Gossip + DHT: `aegis-discovery/src/main/java/com/aegisos/discovery/`
- Raft: `aegis-consensus/src/main/java/com/aegisos/consensus/`
- File system: `aegis-fs/src/main/java/com/aegisos/fs/`
- Scheduler: `aegis-scheduler/src/main/java/com/aegisos/scheduler/`
- Runtime + checkpoint/migration: `aegis-runtime/src/main/java/com/aegisos/runtime/`
- Public API: `aegis-api/src/main/java/com/aegisos/api/`
- Node wiring + entrypoint: `aegis-node/src/main/java/com/aegisos/node/` (`AegisNode`, `NodeMain`, `NodeConfig`)
- CLI: `aegis-cli/src/main/java/com/aegisos/cli/`
- Tests + harness + sample jobs: `aegis-test-cluster/src/test/java/com/aegisos/cluster/`

---

_Last updated after runtime validation. Status: code-complete across all 6 phases + cross-cutting; **full test suite passing** (`mvn -q clean test`)._

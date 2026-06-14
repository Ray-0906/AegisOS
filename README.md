# AegisOS v1.0 RC1

A secure, distributed operating-system runtime over a private peer-to-peer network.
Every node is identical and autonomous; there is no central server. Nodes discover each
other, agree on shared state via Raft, store files redundantly in AegisFS, and run jobs
that survive machine failure with checkpoint recovery and locality-aware scheduling.

**Release notes:** [RELEASE_NOTES_v1.0_RC1.md](RELEASE_NOTES_v1.0_RC1.md)

---

## Quick Start (~5 minutes)

**Requirements:** JDK 21+, Maven 3.9+

### 1. Build

```bash
mvn clean package
```

Produces the CLI JAR at `aegis-cli/target/aegis.jar`.

### 2. Start a 3-node cluster

Open three terminals. Run from the repository root.

**Terminal 1 — seed node:**
```bash
java -jar aegis-cli/target/aegis.jar start --home node1 --port 9001 --bootstrap
```

**Terminal 2:**
```bash
java -jar aegis-cli/target/aegis.jar start --home node2 --port 9002 --seed 127.0.0.1:9001
```

**Terminal 3:**
```bash
java -jar aegis-cli/target/aegis.jar start --home node3 --port 9003 --seed 127.0.0.1:9001
```

Wait a few seconds for gossip and Raft to converge.

### 3. Verify the cluster

In a fourth terminal:

```bash
java -jar aegis-cli/target/aegis.jar nodes --seed 127.0.0.1:9001
```

You should see three nodes with `ALIVE` status.

### 4. Put and get a file

```bash
echo "Hello AegisOS" > hello.txt

java -jar aegis-cli/target/aegis.jar put hello.txt /hello.txt --seed 127.0.0.1:9001

java -jar aegis-cli/target/aegis.jar get /hello.txt recovered.txt --seed 127.0.0.1:9002

type recovered.txt    # Windows
# cat recovered.txt   # Linux/macOS
```

### 5. Automated smoke test (Windows)

```powershell
powershell -ExecutionPolicy Bypass -File test_client_quorum.ps1
```

Starts a cluster, runs put/get operations, kills a node, and verifies the cluster still works.

---

## Further reading

| Document | Purpose |
|----------|---------|
| [RELEASE_NOTES_v1.0_RC1.md](RELEASE_NOTES_v1.0_RC1.md) | What changed in this release |
| [docs/USAGE.md](docs/USAGE.md) | Extended CLI reference (jobs, artifacts, raft) |
| [docs/ARCHITECTURE_v0.95.md](docs/ARCHITECTURE_v0.95.md) | Architecture diagrams and flows |
| [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) | Current limitations and deferred work |
| [handoff.md](handoff.md) | Engineering handoff (stabilization investigation) |
| [docs/post-v1-roadmap.md](docs/post-v1-roadmap.md) | Post-v1.0 deferred items |

---

## Modules

| Module | Responsibility |
| --- | --- |
| `aegis-core` | Shared types, Ed25519/X25519/AES-GCM crypto, identity, protobuf wire schema |
| `aegis-network` | Authenticated, encrypted transport (handshake + AES-256-GCM channel) |
| `aegis-discovery` | Gossip membership + Kademlia DHT routing |
| `aegis-consensus` | Raft leader election, log replication, cluster state machine |
| `aegis-fs` | Content-addressed, encrypted, self-healing distributed file system |
| `aegis-scheduler` | Resource reporting + locality-aware weighted job placement |
| `aegis-runtime` | Job execution, checkpointing, process migration |
| `aegis-api` | Public OS-like API (file system, process manager, cluster info) |
| `aegis-node` | Boots and wires all layers into one JVM process |
| `aegis-cli` | The `aegis` command-line interface |
| `aegis-test-cluster` | In-process multi-node integration + chaos tests |

---

## CLI reference

All client commands require `--seed host:port` pointing at a running cluster node.

```text
aegis start [--home DIR] [--port N] [--seed h:p] [--bootstrap]   # run a node
aegis nodes --seed h:p                                             # list cluster members
aegis put <local> <remote> --seed h:p                              # upload file
aegis get <remote> <local> --seed h:p                              # download file
aegis ls [path] --seed h:p                                         # list files
aegis artifact upload <jar> --seed h:p                             # upload job JAR
aegis artifact list --seed h:p                                     # list artifacts
aegis run <JobClass> [--artifact SHA256] --seed h:p                # submit job
aegis status <jobId> --seed h:p                                    # job status
aegis jobs list --seed h:p                                         # list all jobs
```

CLI commands run as ephemeral clients: they connect, execute, and exit without joining the Raft voting quorum.

---

## Tests

```bash
mvn test -pl aegis-test-cluster
```

84 integration tests (~17 min). See [handoff.md](handoff.md) for stabilization context.

Full reactor build:

```bash
mvn clean package
```

---

## Design

See [DetailAndPlanning.md](DetailAndPlanning.md) for the full design and
[docs/](docs/) for focused notes on Raft, gossip, and the security model.

# AegisOS v0.1

A secure, distributed operating-system runtime over a private peer-to-peer network.
Every node is identical and autonomous; there is no central server. Nodes discover each
other, agree on shared state, store files redundantly, and run jobs that survive machine
failure.

## Status

v0.1 MVP. Built phase-by-phase from cryptographic identity up through process migration.

## Modules

| Module | Responsibility |
| --- | --- |
| `aegis-core` | Shared types, Ed25519/X25519/AES-GCM crypto, identity, protobuf wire schema |
| `aegis-network` | Authenticated, encrypted transport (handshake + AES-256-GCM channel) |
| `aegis-discovery` | Gossip membership + Kademlia DHT routing |
| `aegis-consensus` | Raft leader election, log replication, cluster state machine |
| `aegis-fs` | Content-addressed, encrypted, self-healing distributed file system |
| `aegis-scheduler` | Resource reporting + weighted least-loaded job placement |
| `aegis-runtime` | Job execution, checkpointing, process migration |
| `aegis-api` | Public OS-like API (file system, process manager, cluster info) |
| `aegis-node` | Boots and wires all layers into one JVM process |
| `aegis-cli` | The `aegis` command-line interface |
| `aegis-test-cluster` | In-process multi-node integration + chaos tests |

## Requirements

- JDK 21+ (uses virtual threads)
- Maven 3.9+
- Network access to Maven Central for the protobuf `protoc` artifact on first build

## Build

```bash
mvn -q clean package
```

Protobuf sources under `aegis-core/src/main/proto` are compiled by the
`protobuf-maven-plugin` during the build.

## Run a node locally

By default, AegisOS uses `~/.aegis` as the home directory. To run multiple nodes on the same machine, you must give each node a unique home directory and port.

**Node 1 (Seed)**:
```bash
java -XX:+UseZGC -jar aegis-cli/target/aegis.jar start --home node1 --port 9001
```

**Node 2**:
```bash
java -jar aegis-cli/target/aegis.jar start --home node2 --port 9002 --seed 127.0.0.1:9001
```

**Node 3**:
```bash
java -jar aegis-cli/target/aegis.jar start --home node3 --port 9003 --seed 127.0.0.1:9001
```

## CLI

```text
aegis info                         # show this node's identity
aegis start [--port N] [--seed h:p]# run a node
aegis nodes --seed h:p             # list cluster members
aegis put <local> <path> --seed h:p
aegis get <path> <local> --seed h:p
aegis ls [path] --seed h:p
aegis run <JobClass> [args...] --seed h:p
aegis status <jobId> --seed h:p
```

_Note: CLI commands run as ephemeral clients that connect to the cluster, execute the command, and exit without joining the Raft voting quorum._

## Tests

```bash
mvn -q test
```

Phase gate tests live in `aegis-test-cluster` (`Phase1Test` .. `Phase6Test`) and spin up
real in-process clusters.

### Local End-to-End Test Script

A PowerShell script is included to automatically test cluster resilience against transient client operations and node failures:

```powershell
powershell -ExecutionPolicy Bypass -File test_client_quorum.ps1
```

This script:
1. Starts a 3-node local cluster.
2. Runs 6 rapid CLI operations (`put` and `get`), creating transient clients.
3. Kills Node 3 to test failover and leader election.
4. Verifies the cluster still accepts `put` and `get` operations.

## Design

See [DetailAndPlanning.md](DetailAndPlanning.md) for the full design and
[docs/](docs/) for focused notes on Raft, gossip, and the security model.

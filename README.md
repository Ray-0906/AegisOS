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

## Run a node

```bash
java -XX:+UseZGC -jar aegis-cli/target/aegis.jar start --port 9000
```

Start more nodes pointing at a seed:

```bash
java -jar aegis-cli/target/aegis.jar start --port 9001 --seed 127.0.0.1:9000
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

## Tests

```bash
mvn -q test
```

Phase gate tests live in `aegis-test-cluster` (`Phase1Test` .. `Phase6Test`) and spin up
real in-process clusters.

## Design

See [DetailAndPlanning.md](DetailAndPlanning.md) for the full design and
[docs/](docs/) for focused notes on Raft, gossip, and the security model.

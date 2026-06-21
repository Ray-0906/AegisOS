# Architecture Ports (v1.3)

AegisOS nodes require strict separation of concerns for network traffic. As defined by **INV-040**, client-facing APIs, cluster observability, and internal cluster communication must be physically separated into distinct ports and handlers.

## Port Assignments

AegisOS reserves three distinct ports per node, offset by standard intervals:

### 1. P2P Transport Port (Default: 9001)
- **Purpose**: Internal cluster communication only.
- **Protocols**: Custom TCP serialization.
- **Traffic**: Gossip membership, Raft leader elections, Raft `AppendEntries` replication, Storage layer chunk synchronization.
- **Accessibility**: Node-to-node only. Must be secured in production (e.g., within a private VPC).

### 2. Observability / Metrics Port (Default: 19001, `P2P + 10000`)
- **Purpose**: Telemetry and cluster insight.
- **Protocols**: HTTP (Prometheus Exporter format).
- **Traffic**: Prometheus scraping, metrics extraction.
- **Accessibility**: Operator network. Read-only. Does not support cluster mutation.

### 3. REST API Port (Default: 20001, `P2P + 11000`)
- **Purpose**: External client interface.
- **Protocols**: HTTP / REST / JSON.
- **Traffic**: `aegis-client` SDK requests, CLI commands, Job submissions, File operations (put/get), Raft administration.
- **Accessibility**: Public / Client network. All traffic hitting this port is handled by a strictly decoupled REST server.

## Port Assignment Example (3-Node Cluster)

| Node | P2P (Internal) | Metrics (Operator) | REST API (Public) |
|------|----------------|--------------------|-------------------|
| Node1| 9001           | 19001              | 20001             |
| Node2| 9002           | 19002              | 20002             |
| Node3| 9003           | 19003              | 20003             |

## Implementation Details

- The REST API Server will be implemented using the `com.sun.net.httpserver.HttpServer` from the standard JDK.
- The `MetricsServer` and `RestApiServer` must use separate `HttpServer` instances to prevent accidental handler overlaps.

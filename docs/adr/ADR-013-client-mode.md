# ADR 013: Separate Client Mode From Cluster Node Mode

## Status
Accepted (Deferred for v0.2)

## Context
In AegisOS v0.1, the Command Line Interface (CLI) tools (`put`, `get`, `ls`, `run`, `status`) behave as ephemeral cluster members. When a user runs a CLI command, the CLI process boots a temporary AegisNode, joins the cluster, participates in the Gossip protocol to discover peers, waits for Raft convergence to learn the leader, issues the command locally via the `AegisOS` API, and then immediately shuts down.

While functional for initial bootstrapping, this architecture is overly heavyweight for a client interface:
- It races cluster convergence, necessitating sleep loops in the client simply to prevent `NotLeaderException`.
- A CLI client should not participate in the cluster's internal state machine or P2P mesh network.
- As the cluster grows, the overhead of ephemeral nodes joining and leaving the cluster creates unnecessary noise in Gossip and Raft.
- Typical distributed systems (like `etcdctl`, `kubectl`, or `redis-cli`) use lightweight RPC clients rather than full node instances.

## Decision
For v0.2, we will redesign the CLI to act as a lightweight RPC Client rather than an ephemeral Node. 

The future workflow will be:
1. The CLI connects to a provided seed node via a lightweight RPC connection (e.g., using `NetworkLayer.request()`).
2. The CLI asks the seed for the current Raft leader.
3. The CLI is redirected to the leader (or the seed forwards the request transparently).
4. The CLI submits the command.

This completely removes the need for the CLI to bind a P2P listen port, participate in Gossip, or instantiate a `ConsensusModule`. 

## Consequences
- **Positive:** CLI execution will be significantly faster and more robust, with no race conditions around leader discovery.
- **Positive:** The cluster will no longer experience temporary membership churn when users execute CLI commands.
- **Negative:** We must explicitly design and expose a Client RPC protocol/API on top of the existing `NetworkLayer`, which requires additional message types and handler logic.

## Deferred Implementation
This redesign is deferred until after the v0.1 stabilization phase is complete. For v0.1, a patch has been introduced to ensure the ephemeral node explicitly waits for `node.consensus().leaderId() != null` (max 5 seconds) before proceeding with the operation, which reliably circumvents the symptom without modifying the architecture.

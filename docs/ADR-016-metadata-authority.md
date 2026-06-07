# ADR-016: Raft Metadata Authority & Evidence

## Status
Accepted

## Context
As AegisOS scales, failures, network partitions, and host crashes will cause physical node states to diverge from the cluster's expected state. We need a fundamental principle to resolve conflicts between what a node *thinks* is true and what the cluster *agrees* is true.

## Decision
**Raft metadata is authoritative. Observed state is evidence.**

1. The Raft state machine (`ClusterStateMachine`) is the absolute source of truth for all cluster state, including file chunk locations, job assignments, and node membership.
2. The physical state observed on a node (files on disk, running processes) is merely local evidence.
3. If local evidence conflicts with Raft metadata, the Raft metadata is correct by definition. The system must act to bring the local physical state into compliance with the Raft metadata (e.g., quarantine orphan chunks, restart lost jobs).

## Consequences
- Repair logic is simplified: the target state is always the Raft state.
- False positives in local state detection will not corrupt the cluster state, provided repair actions require sufficient evidence (see ADR-017).
- Prevents conflicting fixes where nodes attempt to update Raft based on local divergence without formal reconciliation.

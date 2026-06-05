# AegisFS Storage Invariants

The AegisFS storage system guarantees physical data integrity and cluster consistency by strictly adhering to the following invariants. These rules govern how the `ChunkStore`, `FileIndex`, `AntiEntropyManager`, `ChunkScrubber`, and `SelfHealingReaper` interact.

## Invariant 1: Metadata is truth.
The Raft-backed `FileIndex` is the absolute source of truth. Physical disk state must always conform to metadata. If metadata says a chunk exists on Node X, Node X must hold it. If metadata does not list Node X as a holder, Node X must not hold it.

## Invariant 2: Replica count <= RF.
The cluster will never intentionally store more replicas of a chunk than the configured Replication Factor (RF). Excess replicas are treated as violations and removed.

## Invariant 3: Corrupt replicas are never repair sources.
A chunk that fails SHA-256 verification or is otherwise marked corrupt by the `ChunkScrubber` will never be used as a source for self-healing operations. Corruption is strictly contained.

## Invariant 4: Missing replicas are removed before healing.
If a chunk is physically missing from a node but expected by metadata, the metadata must first be corrected by issuing a `REMOVE_REPLICA` command. Only after the metadata correctly reflects the missing state can the `SelfHealingReaper` issue an `ADD_REPLICA` command to heal the cluster back to the RF.

## Invariant 5: Physical chunks not referenced by metadata become quarantined.
Any chunk found on disk by the `AntiEntropyManager` that is not referenced in the `FileIndex` (or where the local node is not listed as a holder) is considered an orphan. Orphans are quarantined to prevent silent disk bloat.

## Invariant 6: A chunk may only be healed from a HEALTHY holder.
The `SelfHealingReaper` will only initiate a repair if at least one node holding the chunk is confirmed healthy and reachable. If only corrupt replicas remain, or if all holders are down, repair is refused.

## Invariant 7: State machine replay reconstructs FileIndex exactly.
A complete cluster restart that replays the Raft log will perfectly reconstruct the `FileIndex` metadata state, allowing nodes to seamlessly resume Kademlia routing, Anti-Entropy sweeps, and Reaper healing.

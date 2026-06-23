# Membership Coupling Audit (Sprint 1A)

As part of the Sprint 1A discovery and vertical slice execution, an architectural coupling issue was identified between the Gossip and Raft layers. This document serves as an inventory of where Raft derives its membership from Gossip.

This audit is intended for Sprint 3 (Reconciliation and Cluster Maintenance) where dynamic membership will be formalized.

## Problem Statement

Currently, the Raft `votingPeers` set is derived dynamically by filtering the active Gossip `MembershipList`. This violates the principle that "Raft metadata is authoritative." If Gossip converges slowly or nodes are marked `DEAD` incorrectly, the Raft quorum size can fluctuate unpredictably.

## Inventory of Coupling Points

### 1. `AegisNode.java`

**Location:** `AegisNode.java` (around `start()` initialization of `votingPeers` and `allPeers` suppliers)

**Description:**
`AegisNode` passes a `Supplier<List<NodeId>> votingPeers` to `ConsensusModule`. 
The supplier is implemented as:
```java
java.util.function.Supplier<java.util.List<com.aegisos.core.identity.NodeId>> votingPeers = () -> {
    java.util.List<com.aegisos.core.identity.NodeId> voters = discovery.membership().allPeers().stream()
            .filter(peer -> peer.getRole() == com.aegisos.proto.NodeRole.CLUSTER_MEMBER)
            .filter(peer -> peer.getStatus() != com.aegisos.proto.PeerStatus.DEAD)
            .map(peer -> com.aegisos.core.identity.NodeId.of(peer.getNodeId().toByteArray()))
            .filter(peerId -> !identity.nodeId().equals(peerId))
            .toList();
    // ...
    return voters;
};
```
**Impact:** 
If a node is transiently marked `DEAD` by Gossip, it is dropped from the `votingPeers` supplier. This causes the remaining Raft nodes to recalculate the majority threshold on-the-fly without a formal Raft joint consensus configuration change.

### 2. `RaftNode.java`

**Location:** `RaftNode.java` (in `startElection()`, `checkQuorum()`, etc.)

**Description:**
`RaftNode` invokes `votingPeers.get()` on every election and `AppendEntries` majority calculation. It does not store the configuration in the Raft log.

**Impact:**
Because `RaftNode` defers to the supplier (which defers to Gossip), the cluster has no stable view of its size. Two nodes might have different quorum thresholds during a network partition based on their local Gossip state.

## Recommended Fix Strategy (For Sprint 3)

1. Introduce a formal `ClusterConfiguration` object that is appended to the Raft log.
2. Only change the `votingPeers` set via a Raft `ConfigurationChange` proposal.
3. Gossip should continue to track *liveness* (for routing and metrics), but it must NOT dictate Raft's *quorum size*.

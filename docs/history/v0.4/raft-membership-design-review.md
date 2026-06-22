# Raft Membership Design Review

> Pre-Sprint 3 design review. This document must be reviewed and approved before
> any code is written. Sprint 3 modifies the consensus model. A mistake here is
> more expensive than anything in Sprint 1 or Sprint 2.

## Status
APPROVED — All review corrections incorporated (v3).

## Date
2026-06-07

---

## The Problem Being Solved

Today, Raft quorum membership is dynamically derived from Gossip at call time:

```java
// AegisNode.java L89-104
Supplier<List<NodeId>> votingPeers = () -> {
    return discovery.membership().allPeers().stream()
            .filter(peer -> peer.getRole() == NodeRole.CLUSTER_MEMBER)
            .filter(peer -> peer.getStatus() != PeerStatus.DEAD)
            .map(peer -> NodeId.of(peer.getNodeId().toByteArray()))
            .filter(peerId -> !peerId.equals(identity.nodeId()))
            .toList();
};
```

This supplier is called every time `RaftNode` needs its voter list:
- Every election (`startElection()`, L205)
- Every commit advancement (`advanceCommit()`, L348)
- Every leader initialization (`becomeLeader()`, L266)

This means quorum size is an emergent property of Gossip. If Gossip declares a
node DEAD, the quorum shrinks. Two nodes in different partitions can compute
different quorum thresholds. This is fundamentally unsafe.

---

## Q1: How is ClusterConfiguration stored?

### Design

`ClusterConfiguration` is a Raft-replicated state machine object.

```java
public final class ClusterConfiguration {
    private long version;
    private Set<NodeId> voters;
    private Set<NodeId> observers;
}
```

`version` is mandatory. It is incremented on every `ADD_VOTER` or `REMOVE_VOTER`
apply. It is needed for:
- Future snapshot serialization
- Future joint consensus transitions
- Debugging and observability (operators can see which version the cluster is on)

It is **not** stored as a separate file. It is part of the state machine, just
like `FileIndex` and `JobRegistry`. It is populated by applying
`ADD_VOTER` and `REMOVE_VOTER` commands from the Raft log.

### Where it lives

`ClusterConfiguration` will live in `aegis-consensus`, alongside
`ClusterStateMachine`. It is registered as a state machine applier:

```java
stateMachine.register(CommandType.ADD_VOTER, configuration::applyAddVoter);
stateMachine.register(CommandType.REMOVE_VOTER, configuration::applyRemoveVoter);
```

### Why not a separate persistent file?

The same reasoning as `FileIndex`: the Raft log is authoritative. The
configuration is the materialized view. On restart, replaying the log
reconstructs it deterministically. A separate file creates a reconciliation
problem between two sources of truth.

---

## Q2: How does bootstrap work?

### Two startup modes

There are exactly two ways a node can start. They are mutually exclusive.

#### Bootstrap mode (cluster creation)

```bash
aegis start --bootstrap
```

Used **only** to create a brand new cluster. Creates:

```text
ClusterConfiguration
    version = 1
    voters = {self}
    observers = {}
```

The node is immediately electable and will win a single-node election.

#### Join mode (default)

```bash
aegis start --seeds 127.0.0.1:9000
```

Used for every subsequent node. Creates:

```text
ClusterConfiguration
    version = 0
    voters = {}
    observers = {}
```

The node is **not electable**. It cannot start elections. It cannot become leader.
It must learn the real configuration from an existing leader via Raft log replication.

### Why two modes?

A joining node must **never** temporarily believe it owns a quorum.

If every node creates `voters = {self}` as genesis, there is a window where a
new node thinks it is a valid single-node cluster before it hears from the leader.
During that window, it could self-elect, accept writes, and diverge.

By making join-mode nodes start with `voters = {}`, they are structurally
prevented from participating in elections until an existing leader replicates
the real configuration to them.

### How join-mode nodes learn the configuration

1. Node starts with `voters = {}`. Not electable.
2. Gossip discovers existing cluster members.
3. The existing leader sends `AppendEntries` heartbeats (via `allPeers`, which
   includes all Gossip-discovered nodes).
4. The joining node receives and applies log entries, including any prior
   `ADD_VOTER` commands. Its `ClusterConfiguration` is now populated.
5. The node is still not a voter (it hasn't been added yet).
6. The operator runs `aegis raft add-voter <nodeId>`.
7. The leader proposes `ADD_VOTER`. On commit, the node becomes a voter.

---

## Q3: How does first cluster creation work?

A fresh 3-node cluster is bootstrapped as follows:

### Step 1: First node bootstraps

```bash
aegis start --bootstrap --port 9000
```

Node A starts with bootstrap configuration `voters = {A}`, wins single-node
election, appends NO-OP.

```text
ClusterConfiguration = { version: 1, voters: [A], observers: [] }
```

### Step 2: Second node joins

```bash
aegis start --port 9001 --seeds 127.0.0.1:9000
```

Node B starts in join mode (`voters = {}`). Discovers A via Gossip. Receives
log entries from leader A. Cannot vote, cannot start elections.

### Step 3: Operator promotes B to voter

```bash
aegis raft add-voter <nodeB-id> --target 127.0.0.1:9000
```

Leader proposes `ADD_VOTER(B)`. On commit:

```text
ClusterConfiguration = { version: 2, voters: [A, B], observers: [] }
```

### Step 4: Repeat for node C

```bash
aegis start --port 9002 --seeds 127.0.0.1:9000
aegis raft add-voter <nodeC-id> --target 127.0.0.1:9000
```

```text
ClusterConfiguration = { version: 3, voters: [A, B, C], observers: [] }
```

Explicit, auditable, no Gossip-triggered mutations.

---

## Q4: How does AddVoter work?

### Safety preconditions (enforced by the leader before proposing)

> **ADD_VOTER may only succeed if the target node is reachable and caught up.**

Before the leader proposes `ADD_VOTER(X)`:

1. **Existence**: Node X must be known to the leader (present in Gossip or
   recently connected). If completely unknown, the command is rejected.
2. **Reachability**: Node X must be ALIVE or SUSPECT in Gossip. If DEAD or
   absent, the command is rejected.
3. **Replication lag**: Node X's `matchIndex` in the `LogReplicator` must be
   within an acceptable window of the leader's `lastIndex` (e.g., within 10
   entries). If the node is far behind, adding it as a voter increases quorum
   size while the node cannot contribute to majority, stalling commits.

If any precondition fails, the leader returns an error immediately without
proposing to Raft.

### Serialization constraint: one change at a time

> **CONSTRAINT: Only one configuration change may be in-flight at a time.**
>
> `ADD_VOTER(A)` must commit before `ADD_VOTER(B)` can begin.

This is enforced structurally at the leader. If a configuration change is pending
(proposed but not committed), the leader rejects any new configuration submission
with: `"configuration change already in progress"`.

### Protocol

1. Operator runs `aegis raft add-voter <nodeId>`.
2. CLI constructs `StateCommand { type: ADD_VOTER, payload: <node_id bytes> }`.
3. CLI sends this as a `CLIENT_COMMAND` to the leader.
4. Leader validates preconditions (reachable, caught up).
5. Leader checks no other config change is in-flight.
6. Leader calls `raftNode.submit(bytes)`.
7. Entry is appended, replicated, committed.
8. `ClusterStateMachine.apply()` dispatches to `ClusterConfiguration.applyAddVoter()`.

### applyAddVoter semantics

```java
public void applyAddVoter(long index, StateCommand cmd) {
    NodeId nodeId = NodeId.of(cmd.getPayload().toByteArray());
    if (voters.contains(nodeId)) {
        log.info("ADD_VOTER ignored: {} is already a voter", nodeId.shortId());
        return; // idempotent
    }
    voters.add(nodeId);
    observers.remove(nodeId);
    version++;
    log.info("ADD_VOTER applied: {} is now a voter (version {})", nodeId.shortId(), version);
}
```

### Safety note: no joint consensus in v0.4

Single-server change model (Raft dissertation §4.1). Safe as long as membership
changes are serialized, which is enforced by the in-flight constraint above.

---

## Q5: How does RemoveVoter work?

### Protocol

Identical to AddVoter, but with `CommandType.REMOVE_VOTER`.

Subject to the same **one-change-at-a-time** constraint.

Reachability precondition is NOT required for RemoveVoter (you should be able to
remove a dead node).

### applyRemoveVoter semantics

```java
public void applyRemoveVoter(long index, StateCommand cmd) {
    NodeId nodeId = NodeId.of(cmd.getPayload().toByteArray());
    if (!voters.contains(nodeId)) {
        log.info("REMOVE_VOTER ignored: {} is not a voter", nodeId.shortId());
        return; // idempotent
    }
    voters.remove(nodeId);
    version++;
    log.info("REMOVE_VOTER applied: {} removed from voters (version {})", nodeId.shortId(), version);
}
```

### Edge case: leader removes itself

1. Command committed using old configuration (leader still a voter).
2. On apply, leader's `ClusterConfiguration` removes itself.
3. Leader steps down to FOLLOWER.
4. Remaining voters elect a new leader.

### Edge case: removing the last voter

Rejected at the leader level. Cannot reduce voters below 1.

---

## Q6: What happens during leader failover?

### Scenario: Leader A dies, B and C remain

1. B and C stop receiving heartbeats. Election timer expires.
2. B (or C) calls `startElection()`.
3. `startElection()` calls `votingPeers.get()`.
4. `votingPeers.get()` reads from `ClusterConfiguration` → returns `{A, B, C}`
   minus self.
5. `clusterSize = 2 + 1 = 3`. `majority = 2`.
6. B requests votes from A (unreachable) and C.
7. If C grants its vote, B has 2 votes ≥ majority. B becomes leader.

### Critical difference from today

Today, Gossip declaring A DEAD shrinks `votingPeers` → quorum becomes majority
of 2 instead of 3. With `ClusterConfiguration`, quorum is **stable**. Dead nodes
still count in the denominator.

This means: in a 3-node cluster, losing 2 nodes makes the cluster unavailable
(correct behavior). Today, losing 2 nodes allows the survivor to self-elect
(split-brain vector).

### Permanently dead node?

Operator must explicitly `aegis raft remove-voter <dead-node-id>`. Automatic
quorum shrinking is the exact problem we're eliminating.

---

## Q7: What happens during restart?

### Node restart sequence

1. `RaftLog` loads persisted entries from `log.bin`.
2. `RaftMetadataStore` loads `currentTerm` and `votedFor` from `meta.properties`.
3. `ClusterConfiguration` starts empty (join-mode default: `version=0, voters={}`).
4. `consensus.replayFromLog()` replays every committed entry, including all
   `ADD_VOTER` and `REMOVE_VOTER` entries.
5. After replay, `ClusterConfiguration` is fully reconstructed from the log.
6. `consensus.start()` begins ticking.
7. `votingPeers.get()` returns the reconstructed configuration.

### Invariant

At all times, a node's `ClusterConfiguration` is exactly the result of applying
all `ADD_VOTER` and `REMOVE_VOTER` commands in the Raft log up to its
`lastApplied` index. There is no other source.

### Note on bootstrap vs restart

On restart, an existing log always exists (at minimum the NO-OP entry). The
`--bootstrap` genesis is only relevant for the very first startup with a fresh
data directory. On restart, the log replay overwrites any initial state.

---

## Q8: How will snapshots serialize configuration later?

### Current state

No snapshot support exists. Sprint 6 is designated for snapshots.

### Design intent

```protobuf
message ClusterConfigurationSnapshot {
    uint64 version = 1;
    repeated bytes voter_node_ids = 2;
    repeated bytes observer_node_ids = 3;
}
```

Included in the snapshot envelope alongside `FileIndex`, `JobRegistry`, etc.
On restart with snapshot: load snapshot state, then replay entries `[N+1, ...]`.

Safe because `ClusterConfiguration` is a deterministic function of the log.

---

## RaftNode Changes Required

The original design claimed `RaftNode.java` needs no modifications.
**This is incorrect.** The following changes are required:

### 1. `isVotingMember` must become dynamic

Today:
```java
private final boolean isVotingMember;  // L46, set at construction
```

This is checked in `onElectionTimeout()` (L186). A join-mode node starts with
`isVotingMember = false` and can never start elections — even after `ADD_VOTER(self)`
is committed and applied to `ClusterConfiguration`.

**Fix**: Replace the `boolean` with a `BooleanSupplier` that queries
`ClusterConfiguration.isVoter(self)`:

```java
private final BooleanSupplier isVotingMember;
```

This makes the election gate dynamic: as soon as `ADD_VOTER(self)` is applied,
the node becomes electable on the next timeout.

### 2. Quorum computation verification

Today, quorum is computed from `votingPeers.get()`:

```java
// startElection() L205-207
List<NodeId> peers = votingPeers.get();
int clusterSize = peers.size() + 1;  // assumes self is always a voter
int majority = clusterSize / 2 + 1;

// advanceCommit() L348-350
List<NodeId> peers = votingPeers.get();
int clusterSize = peers.size() + 1;  // same assumption
int majority = clusterSize / 2 + 1;
```

The `+ 1` assumes self is a voter. This is true in bootstrap mode, but in
join mode, self is NOT a voter until `ADD_VOTER(self)` is applied. The non-voting
node shouldn't be computing quorum at all (blocked by `isVotingMember` gate),
but we should verify there are no edge cases where it could reach this code path.

**Recommended**: Make `votingPeers` supplier return **all voters excluding self**
(unchanged from current behavior). The `+ 1` for self is valid because:
- Only voters start elections (`isVotingMember` gate)
- Only the leader advances commits (and the leader is always a voter)

The quorum formula itself is correct. The fix is ensuring the gate
(`isVotingMember`) prevents non-voters from entering these code paths.

### 3. `handleRequestVote` behavior for non-voters

Today, `handleRequestVote()` (L402-426) does NOT check `isVotingMember`. A
non-voting node can still **grant votes** to candidates. This is actually
correct per the Raft paper — vote granting is not restricted to voters. Any
node that receives a valid `RequestVote` should respond. No change needed here.

### Summary of RaftNode changes

| Area | Change needed? | Reason |
| --- | --- | --- |
| `isVotingMember` field | **YES** → `BooleanSupplier` | Must become dynamic to allow promotion |
| `startElection()` quorum | No | `+ 1` is valid because gate ensures only voters reach this |
| `advanceCommit()` quorum | No | Only leader calls this, and leader is always a voter |
| `becomeLeader()` | No | Uses `votingPeers.get()` which reads from ClusterConfiguration |
| `handleRequestVote()` | No | Non-voters correctly grant votes per Raft paper |

---

## Structural Constraint

> **Only one configuration change may be in-flight at a time.**
>
> ADD_VOTER(A) must commit before ADD_VOTER(B) can begin.
>
> Enforced at the leader. If a configuration change is pending (proposed but
> not committed), the leader rejects new configuration submissions.

---

## Implementation Touchpoints

### New protobuf additions (`aegis.proto`)

```protobuf
ADD_VOTER       = 12;
REMOVE_VOTER    = 13;
```

### Files to create

| File | Module | Purpose |
| --- | --- | --- |
| `ClusterConfiguration.java` | `aegis-consensus` | Raft-replicated voter/observer set with version |

### Files to modify

| File | Change |
| --- | --- |
| `aegis.proto` | Add `ADD_VOTER = 12`, `REMOVE_VOTER = 13` to `CommandType` |
| `RaftNode.java` | Change `isVotingMember` from `boolean` to `BooleanSupplier` |
| `AegisNode.java` | Replace Gossip `votingPeers` with `ClusterConfiguration`; add `--bootstrap` flag handling |
| `ConsensusModule.java` | Own `ClusterConfiguration`; genesis/join initialization |
| `MetricsServer.java` | Update `/membership` with `raftConfiguration` block |
| `NodeConfig.java` | Add `bootstrap` flag |
| CLI module | Add `aegis raft add-voter` and `aegis raft remove-voter` commands |

### Files NOT modified

| File | Reason |
| --- | --- |
| `RaftLog.java` | No changes needed |
| `RaftMetadataStore.java` | Configuration lives in the log, not in metadata |
| `MembershipList.java` | Gossip tracks liveness independently |

---

## Verification Plan

### Primary Acceptance Test: PartitionSafetyTest

```text
Boot 5 nodes: A B C D E (all voters)
Simulate partition: {A, B} | {C, D, E}
Verify: only majority side {C, D, E} elects a leader
Verify: minority side {A, B} does NOT elect a leader
Heal partition
Verify: single leader remains
Verify: minority converges to majority's leader
```

### Supporting Tests

1. **SingleNodeBootstrapTest**: `--bootstrap` → genesis seeds → leader election succeeds.
2. **JoinModeNonElectableTest**: Join-mode node → assert it cannot start elections → assert it cannot become leader even after timeout.
3. **ConfigurationSurvivesRestartTest**: 3 voters → kill all → restart → assert config intact.
4. **ConfigurationSurvivesLeaderChangeTest**: Add voter → kill leader → new leader has correct config.
5. **RaftQuorumIsolationTest**: 3 voters → kill 1 → Gossip DEAD → `votingPeers.size() == 2`, quorum still majority-of-3.
6. **AddOfflineVoterRejectedTest**: A, B running → `ADD_VOTER(C)` where C does not exist → expect rejection.
7. **VoterPromotionTest**: Join-mode node → `ADD_VOTER(node)` → assert node becomes electable.

# Gossip & Discovery Notes (aegis-discovery)

Discovery answers two questions cheaply and without a central registry: *who is in the
cluster?* and *which of them are alive?*

## Membership

`MembershipList` holds one entry per known node:

- status `ALIVE | SUSPECT | DEAD`,
- last-seen timestamp, monotonically increasing version, endpoint, and public key.

State transitions (relative to the gossip interval `T`):

- `ALIVE → SUSPECT` after `3T` with no fresh news.
- `SUSPECT → DEAD` after `10T`.
- Any fresh observation resurrects a node to `ALIVE` with a higher version.

The local node is always `ALIVE` to itself.

## Gossip protocol

- Every ~1 s each node picks `K = 3` random peers and does a push-pull exchange of
  membership snapshots.
- Snapshots are merged by `(version, timestamp)`: the fresher view of each node wins. This
  is a CRDT-style union-merge, so the order of exchanges doesn't matter — the cluster
  converges.
- Failure detection is emergent: if nobody can refresh a node, every node's timers age it to
  SUSPECT then DEAD at roughly the same wall-clock time.

## Bootstrap

A new node reads seed endpoints (`--seed` / `seeds.conf`), connects over `aegis-network`,
and pulls the seed's membership list. From there gossip takes over.

## Kademlia DHT

`RoutingTable` + `KademliaRouter` provide XOR-distance-based routing and `FIND_NODE`. This
underpins content placement in `aegis-fs` (chunks live on the nodes whose ids are closest to
the chunk id).

## Gate (Phase 2)

5 nodes converge to a common view in < 15 s; an offline node is detected in < 10 s.
Covered by `Phase2Test`.

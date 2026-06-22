# Raft Notes (aegis-consensus)

Raft gives AegisOS a single, strongly-consistent, replicated log. The cluster's shared
metadata (file registry, chunk placement, job assignments, membership changes) is just the
result of applying that log to a deterministic state machine.

## Roles

`FOLLOWER → CANDIDATE → LEADER`. Exactly one leader per term under normal operation.

## Timing

- Election timeout: randomized 150–300 ms (randomization avoids split votes).
- Heartbeat (empty `AppendEntries`): 50 ms from the leader.
- A follower that hears nothing within its timeout starts an election in a new term.

## Log

- `RaftLog` is an append-only file. New tail entries are appended incrementally; only a
  conflict/truncation forces a full rewrite (heartbeats never rewrite the file).
- Each entry: `{ index, term, command bytes }`.
- `RaftMetadataStore` separately persists `currentTerm` and `votedFor` so a restarted node
  never double-votes in a term.

## Replication & commit

- Leader appends locally, replicates via `AppendEntries`, and advances `commitIndex` once a
  majority has the entry (`LogReplicator` tracks per-follower `nextIndex`/`matchIndex`).
- Committed entries are applied in order to `ClusterStateMachine`, which dispatches each
  `CommandType` to the registered applier (file index, job registry, KV, …).

## Client commands

- `ConsensusModule.propose` submits a command. On a follower it transparently forwards to
  the current leader over `aegis-network` (`CLIENT_COMMAND`) and waits for commit.
- Non-leaders that don't know a leader yet fail fast (`NotLeaderException`).

## Gate (Phase 3)

3-node cluster elects a leader, replicates 1000 entries, and recovers within ~500 ms after
the leader is killed. Covered by `Phase3Test` and `Phase3ChaosTest`.

## v0.1 limitations / later work

- No log compaction / snapshotting yet — the log grows unbounded.
- File persistence first; a RocksDB-backed log is planned.
- No membership-change (joint-consensus) protocol; cluster size is effectively static.

# ADR-020: RPC Correlation Isolation (Historical Incident)

**Status:** Resolved (v1.1)
**Date:** 2026-06-14

---

## Context

During v1.1 stabilization work, `Phase8Test` (100-job load test) exhibited
intermittent `NotLeaderException(null)` failures at approximately 1-in-10
frequency. The failure presented impossible Raft state: the node reported
`leaderId == null` while no `LEADER → FOLLOWER` transition had occurred.

## Root Cause

`NetworkLayer` multiplexes all peer RPC over a shared `correlationCounter`
and stores pending `CompletableFuture` objects keyed by correlation ID.
The receive path completed any pending future matching the inbound
correlation ID **without distinguishing requests from responses**.

Under load, two nodes could simultaneously issue requests that happened to
share the same correlation ID (each node has its own counter starting at 1).
When Node B's **request** arrived at Node A, the `onMessage` handler matched
it against Node A's own **pending future** for an unrelated outbound request:

```text
Node A sends GOSSIP_SYN      correlation=9  → Node B
Node B sends CLIENT_COMMAND   correlation=9  → Node A  (different counter)

Node A receives correlation=9 from Node B
  → pending.remove(9) finds the GOSSIP_SYN future
  → future.complete(CLIENT_COMMAND_RESULT)
  → protobuf deserialization produces garbage
  → NotLeaderException(null)
```

## Diagnostic Evidence

Instrumented `NetworkLayer.request()` and `NetworkLayer.onMessage()` with
type-tracking logs:

```text
[RPC-SEND] creating request correlation=9 expected_type=GOSSIP_SYN
[RPC-RECV] completing correlation=9 type=CLIENT_COMMAND_RESULT
```

A Python analysis script confirmed multiple `expected_type ≠ received_type`
collisions across a single test run.

## Fix

Added a protocol-level `is_response` flag to `MessageHeader` in
`aegis.proto`:

```protobuf
message MessageHeader {
  ...
  bool is_response = 9;
}
```

**Sender side:** Outbound requests set `is_response = false`. Handler
responses set `is_response = true`.

**Receiver side:** `NetworkLayer.onMessage` only completes pending futures
when `correlation != 0 && isResponse == true`. Inbound requests (which
carry `is_response = false`) are always dispatched to the registered
`MessageHandler`, never matched against pending futures.

## Alternatives Considered

### `enum MessageKind { REQUEST = 0; RESPONSE = 1; }`

A two-value enum carries the same information as a boolean but occupies a
varint field instead of a single byte. The enum also invites future
extension (`NOTIFICATION`, `STREAM_CHUNK`, etc.) which would complicate
the completion guard. The invariant is binary — request or response — and
a boolean captures that exactly.

### `uint64 response_to` (correlation of the original request)

This carries strictly more information (which request this responds to)
but introduces a second correlation field. The receiver would need to match
on `response_to` instead of `correlation`, which changes the lookup key.
Every existing code path uses `correlation` as the map key; adding a
parallel key doubles the matching surface and risks partial migrations
where some paths check `correlation` and others check `response_to`.

The `is_response` flag was chosen because it requires **zero changes** to
the existing correlation ID scheme. The same `correlation` value is used
for both request and response; the boolean simply tells the receiver
whether to complete a future or dispatch to a handler.

## Invariants

The transport-layer contract for RPC correlation:

```
1. A request message (is_response = false) MUST NEVER complete a pending future.

2. Only a response message (is_response = true) MAY complete a pending future.

3. A response message that does not match any pending future is silently dropped.

4. A request message with a non-zero correlation is dispatched to the
   registered MessageHandler. If the handler returns a non-null reply,
   the reply is sent back with the same correlation and is_response = true.
```

Any change to `NetworkLayer.onMessage` or `PeerConnection.send` must
preserve all four invariants. `RpcCorrelationIsolationTest` enforces
invariants 1 and 2 directly.

## Regression Test

`RpcCorrelationIsolationTest` (aegis-network) covers both invariants:

1. **`testRequestDoesNotSatisfyFuture`** — Two nodes simultaneously send
   requests with no registered response handler. Both futures must time out;
   neither may be completed by the other node's request.

2. **`testResponseSatisfiesFuture`** — A handler returns a response. The
   requesting node's future must complete with the correct response type.

## Why This Matters for Future Contributors

This bug class is easy to reintroduce:

- Adding a new RPC path that calls `connection.send(...)` without the
  `isResponse` parameter.
- Removing the `&& isResponse` guard from the receive path.
- Any refactor of `PeerConnection.send()` that drops the boolean.

The regression test exists specifically to catch these regressions. Do not
remove or weaken it.

## Files Changed

| File | Change |
|------|--------|
| `aegis-core/src/main/proto/aegis.proto` | Added `bool is_response = 9` to `MessageHeader` |
| `aegis-network/.../PeerConnection.java` | `send()` and `InboundHandler.onMessage()` carry `isResponse` |
| `aegis-network/.../NetworkLayer.java` | Guard future completion on `isResponse == true` |
| `aegis-network/.../RpcCorrelationIsolationTest.java` | Regression test |

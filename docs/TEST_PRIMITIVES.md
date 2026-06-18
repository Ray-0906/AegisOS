# Test Primitives — Invariant Rules

> INV-016: Tests must synchronize on observable events, never on elapsed time.

## Primitives

| Primitive        | Role                         | Location                                     |
| ---------------- | ---------------------------- | -------------------------------------------- |
| `EventAwaiter`   | The only polling primitive   | `aegis-test-cluster/.../testing/`            |
| `ClusterAwaiter` | Thin adapter over harness    | `aegis-test-cluster/.../testing/`            |
| `TestBarrier`    | CountDownLatch wrapper       | `aegis-test-cluster/.../testing/`            |

## Rules

1. **`EventAwaiter` is the only polling primitive.**
   All condition-based waiting must go through `EventAwaiter.await()`.
   No test may implement its own `while` loop with `Thread.sleep`.

2. **`ClusterAwaiter` is a thin adapter.**
   Every method in `ClusterAwaiter` must delegate to `ClusterHarness` state queries
   wrapped in `EventAwaiter.await()`. No direct inspection of Raft internals,
   registries, or runtime structures.

3. **Production code cannot import test primitives.**
   `EventAwaiter`, `ClusterAwaiter`, and `TestBarrier` must never appear in:
   - `aegis-core`
   - `aegis-runtime`
   - `aegis-consensus`
   - `aegis-node`
   - `aegis-fs`
   - `aegis-network`
   - `aegis-scheduler`
   - `aegis-discovery`

4. **Chaos tests cannot use `Thread.sleep()`.**
   Every chaos test must synchronize using `ClusterAwaiter` or `EventAwaiter`.
   Violations are enforced by `scripts/check-no-thread-sleep.sh`.

5. **New awaiters must be domain-specific.**
   Good: `awaitCheckpointCreated()`, `awaitArtifactReplication()`, `awaitWorkerReassignment()`
   Bad: `awaitCondition(Predicate<?>)`, `awaitGeneric(Supplier<Boolean>)`
   Every new method must correspond to a concrete cluster lifecycle event.

## Enforcement

- CI runs `scripts/check-no-thread-sleep.sh` on every PR.
  It uses `git diff` to detect newly introduced `Thread.sleep` in test suites.
- Import leakage is verified by grepping production modules for test primitive imports.
- `ClusterAwaiter` methods are reviewed to ensure they remain thin adapters.

## Adding a New Awaiter

When adding a new method to `ClusterAwaiter`:

1. First, ensure `ClusterHarness` exposes the needed state query (e.g., `isCheckpointPresent()`).
2. Add the method to `ClusterAwaiter` as a thin wrapper:
   ```java
   public void awaitCheckpointCreated(String jobId, int minSequence, Duration timeout) {
       new EventAwaiter().withTimeout(timeout).await(() ->
           harness.isCheckpointPresent(jobId, minSequence));
   }
   ```
3. Do NOT add internal loops, registry lookups, or Raft state inspection inside `ClusterAwaiter`.

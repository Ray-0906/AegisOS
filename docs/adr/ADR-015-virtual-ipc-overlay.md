# ADR-015: The Virtual IPC Overlay

## Status
Accepted

## Context
Process outputs across a distributed system historically act as black boxes. When AegisOS launched native process execution without capturing standard output, it created a structural vulnerability: OS pipe buffers would eventually fill (typically at 64KB), blocking standard outputs indefinitely, and creating zombie processes that consumed cluster resources without completing or failing.

Furthermore, distributed jobs frequently require piping outputs from one physical node's execution context into another physical node's execution context.

## Decision
We establish a Virtual IPC Overlay using standard TCP stream routing and a dedicated stream pumping executor.
1. The `CompletableFuture.runAsync` task aggressively pumps standard output from physical processes, completely eliminating the OS buffer blocking risk.
2. The `ProcessRecord` schema adds `string pipe_to_process_id = 11`.
3. If an emitted stream chunk identifies a valid remote `pipeToProcessId`, the `NetworkLayer` forwards the chunk over the P2P topology using `MessageType.IPC_DATA`.
4. The target node intercepts the stream and pipes the data directly into the receiving process's `OutputStream`.

## Consequences
- **Positive:** System reliability is immune to OS buffer limitations for long-running AI/Polyglot workloads.
- **Positive:** Complex workloads can seamlessly chain processes across network boundaries, simulating the Unix pipe `|` operator across disparate physical machines.
- **Positive:** Thread lifecycle management prevents zombie pumping threads from holding open file descriptors if the parent process dies.
- **Negative:** Extremely high-throughput byte streaming across the overlay network incurs additional CPU and bandwidth costs on the P2P transport layer.

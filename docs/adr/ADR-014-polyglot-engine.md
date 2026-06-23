# ADR-014: The Polyglot Engine

## Status
Accepted

## Context
AegisOS was originally tightly coupled to the Java Virtual Machine, strictly invoking `ProcessBuilder("java", "-jar", ...)` for all background execution workloads. This fundamentally restricted the capability of the distributed operating system to support foreign runtimes, blocking use cases for modern AI agents, bash orchestration, Node.js applications, and compiled C++ payloads.

A true distributed operating system must be agnostic to the underlying physical payload it manages.

## Decision
We decouple the `LocalRuntimeEngine` from JVM-centric assumptions by introducing a dynamic `executionCommand` schema field. 
1. The `ProcessRecordProto` data contract now accepts `string execution_command = 10`.
2. The user payload provides an arbitrary execution string (e.g., `python {artifact}` or `node {artifact}`).
3. The cluster replaces the `{artifact}` token at execution time with the fully resolved absolute path to the local replicated binary in the `AegisFS` storage layer.
4. The `LocalRuntimeEngine` invokes the exact command array provided, falling back to legacy JVM execution if the field is omitted to preserve backward compatibility.

## Consequences
- **Positive:** AegisOS can now orchestrate and distribute workloads across any language, script, or binary format natively supported by the host operating system.
- **Positive:** Clients gain full control over runtime flags and execution environments.
- **Negative:** The host nodes must have the requisite physical runtimes (e.g., Python, Node.js) installed to execute the submitted processes. A node missing a runtime will fail the local process start.

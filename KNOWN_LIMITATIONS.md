# Known Limitations (v0.9)

AegisOS v0.9 is a robust release candidate, but there are a few known limitations and deferred features that developers should be aware of:

### 1. Log Compaction
**Status:** Deferred to Sprint 6 (v1.0 timeline).
Currently, Raft snapshot generation and installation work perfectly, but continuous multi-round log trimming and compaction are not yet fully integrated. The `LogCompactionTest` is explicitly disabled until this state machine coordination is completed.

### 2. Container Runtime Support
**Status:** Deferred to v1.0.
Jobs are currently executed as isolated Java 21 worker processes using `ProcessBuilder` and `ProcessSupervisor`. Native containerization (e.g., Docker/containerd integration) is slated for v1.0.

### 3. Build & IDE Tooling Quirks
**Status:** Workaround Available.
When using VS Code or language servers, there is a known file-system race condition with the Maven compiler plugin that can cause transient `class file not found` errors. 
**Workaround:** Pause or restart the Java language server before running `mvn clean package` locally.

### 4. Test Suite Execution Latency
**Status:** Monitored.
Under heavy parallel load (e.g., during the 17-minute full integration test marathon), certain latency-sensitive integration tests (like `ScratchIsolationTest`) require extended timeouts (30 seconds) to survive JVM scheduling pauses or garbage collection spikes.

# AegisOS Strategic Roadmap (v2.x)

The v1.x architecture established the foundational distributed execution fabric, achieving spatial awareness, physical process supervision, and distributed stream multiplexing.

The v2.x roadmap is defined by a strict engineering hierarchy: Operability and Durability must precede Scale and Features. We will not expand the payload capabilities of the system until we have complete visibility into its operation and guaranteed state recovery for long-running computations.

## Phase 1: Operability (v2.1)
**The Visual Control Plane**
A headless orchestrator is a black box. We will build a comprehensive, real-time observability frontend to expose the internal state machine.
* **Next.js Dashboard:** A real-time web interface consuming AegisOS REST endpoints.
* **Dynamic Topology Mapping:** Visual representation of node health, Gossip telemetry, and Raft consensus stability.
* **Gamified Progression UI:** Implementing dark-mode, high-contrast visual logic to map the lifecycles of distributed workloads into a rank-based progression system, turning cluster management into an engaging, reactive experience.

## Phase 2: Durability (v2.2)
**Stateful Checkpointing & Fault Tolerance**
Currently, a hardware failure during a long-running process results in total progress loss. We will enable true fault tolerance.
* **Checkpoint API:** Fleshing out the `checkpoint()` hook in `LocalRuntimeEngine` to allow running workloads to flush their memory state safely to `AegisFS`.
* **State Injection:** Upgrading the scheduler to automatically retrieve and inject saved state chunks into replacement nodes when recovering a `FAILED` process.

## Phase 3: Scale (v2.3)
**The Polyglot Engine**
AegisOS is currently constrained by a hardcoded Java runtime boundary. We will open the compute layer to the broader software ecosystem.
* **Dynamic Execution:** Modifying `ArtifactRecord` to support custom execution commands.
* **Binary Agnosticism:** Decoupling `LocalRuntimeEngine` from `java -jar` to natively support Python scripts, Node.js payloads, and compiled binaries directly on the host OS.

## Phase 4: Features (v2.4)
**Agentic Orchestration**
Moving beyond static script execution to orchestrating complex, decentralized workflows.
* **Distributed State Graphs:** Evolving the scheduler to natively manage multi-step, cyclic execution paths.
* **AI First-Class Citizens:** Allowing AegisOS to manage multi-agent systems, piping context between disparate nodes (e.g., a data-gathering node streaming state to an analysis node) using the Virtual IPC overlay.
# Architecture Invariants

This document serves as the single source of truth for all AegisOS architectural invariants.

- **INV-025**: executionId = generation
- **INV-026**: ownership before side effects
- **INV-027**: RUNNING semantics
- **INV-028**: async boundaries invalidate ownership
- **INV-029**: workload reality authoritative
- **INV-030**: authority separation
- **INV-031**: unknown is first class
- **INV-033**: terminal states are durable obligations
- **INV-037**: workers never own durability

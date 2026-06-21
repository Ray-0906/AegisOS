# AegisOS Roadmap

## Era 1: Distributed Systems (Completed)
**v0.1 → v1.2.2**

**Goal**: Can machines coordinate?

AegisOS successfully implemented a robust distributed systems core from scratch. The system autonomously manages distributed files, schedules jobs with exactly-once execution semantics, heals replicated storage, and achieves consensus via a bespoke Raft and Gossip implementation.

*Status: FROZEN (v1.2.2)*

## Era 2: Platform Engineering (Upcoming)
**v1.3 → v1.8**

**Goal**: Can humans interact with it?

The project transitions from researching distributed systems algorithms to building a usable, resilient platform. Development in this era must be strictly disciplined: build one milestone at a time, freeze it, and move to the next.

- **v1.3: REST Platform**
  - Replace the ephemeral CLI node architecture with a strict HTTP/REST boundary.
- **v1.4: Native Process Runtime**
  - Execute arbitrary native processes.
- **v1.5: Container Runtime**
  - First-class Docker container support.
- **v1.6: Web Dashboard**
  - A modern, reactive GUI for cluster observability and management.
- **v1.7: Authentication + Users**
  - Secure the cluster.
- **v1.8: Namespaces + Multi-tenancy**
  - Isolate workloads and storage logically.

## Era 3: Cloud Operating System (Future)
**v2.x**

**Goal**: Can applications live on it?

The ultimate vision for AegisOS is to become a complete, self-hosted Cloud Operating System.

- **v2.0: Cloud Operating System**
  - Autoscaling
  - Load balancing
  - Secrets Management
  - Volumes
  - Service Discovery
  - Advanced ACLs

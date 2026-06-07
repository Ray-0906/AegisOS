# ADR-017: Verification Contract (Draft)

## Status
Draft

## Context
When an audit detects divergence between authoritative Raft metadata and local observed evidence (ADR-016), we must verify the divergence before executing a repair. A false-positive reconciliation (where a repair is executed based on faulty audit data) is the highest risk for cluster-wide corruption. We need a strict verification contract to separate auditing, recommendation, and execution.

## 1. Who initiates verification?
*To be filled in during Sprint 2 audit work.*

## 2. What constitutes evidence?
Evidence requirements are **object-specific**.
- **Chunk Replicas:** Same divergence observed across two consecutive scans.
- **Jobs:** Requires multiple signals (e.g., node DEAD + heartbeat expired + job absent on node + two scans).

## 3. What threshold allows repair?
*To be filled in during Sprint 2 audit work.*

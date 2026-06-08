package com.aegisos.fs.audit;

/**
 * Enum-typed verification status for a divergence check.
 *
 * Avoids stringly-typed reason codes and enables exhaustive switching
 * as more divergence types are added in future sprints.
 *
 * Part of the Verification + Recommendation pipeline (Sprint 4).
 */
public enum VerificationStatus {

    /** Divergence is persistent, nodes are alive, current observation confirms it. */
    VERIFIED,

    /** Divergence has not persisted for enough consecutive scans. */
    INSUFFICIENT_HISTORY,

    /** One or more missing-replica nodes are not ALIVE. */
    NODE_UNAVAILABLE,

    /** Historical divergence existed, but current re-observation shows a different divergence shape. */
    OBSERVATION_MISMATCH,

    /** Chunk healed before verification completed. Operationally distinct from OBSERVATION_MISMATCH. */
    NO_LONGER_DIVERGENT
}

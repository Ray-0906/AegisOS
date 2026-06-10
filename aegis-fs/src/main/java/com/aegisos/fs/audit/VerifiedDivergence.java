package com.aegisos.fs.audit;

import java.util.Collections;
import java.util.List;

/**
 * Represents a divergence that has passed all verification checks.
 *
 * Intermediate between {@link VerificationResult} and {@link RepairRecommendation}.
 *
 * Part of the Verification + Recommendation pipeline (Sprint 4).
 */
public final class VerifiedDivergence {

    private final String chunkId;
    private final String divergenceType;
    private final List<Long> evidenceScans;
    private final long verifiedAt;

    public VerifiedDivergence(String chunkId, String divergenceType,
                              List<Long> evidenceScans, long verifiedAt) {
        this.chunkId = chunkId;
        this.divergenceType = divergenceType;
        this.evidenceScans = Collections.unmodifiableList(evidenceScans);
        this.verifiedAt = verifiedAt;
    }

    public String chunkId() { return chunkId; }
    public String divergenceType() { return divergenceType; }
    public List<Long> evidenceScans() { return evidenceScans; }
    public long verifiedAt() { return verifiedAt; }
}

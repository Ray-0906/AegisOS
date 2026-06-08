package com.aegisos.fs.audit;

import java.util.Collections;
import java.util.List;

/**
 * Actionable repair recommendation.
 *
 * Contains no target node, no placement decision, no execution plan.
 * Only states that a divergence is verified and should be repaired,
 * with a complete evidence chain for audit purposes.
 *
 * Part of the Verification + Recommendation pipeline (Sprint 4).
 */
public final class RepairRecommendation {

    private final String chunkId;
    private final String divergenceType;
    private final List<Long> evidenceScans;
    private final long recommendedAt;

    public RepairRecommendation(String chunkId, String divergenceType,
                                List<Long> evidenceScans, long recommendedAt) {
        this.chunkId = chunkId;
        this.divergenceType = divergenceType;
        this.evidenceScans = Collections.unmodifiableList(evidenceScans);
        this.recommendedAt = recommendedAt;
    }

    public String chunkId() { return chunkId; }
    public String divergenceType() { return divergenceType; }
    public List<Long> evidenceScans() { return evidenceScans; }
    public long recommendedAt() { return recommendedAt; }
}

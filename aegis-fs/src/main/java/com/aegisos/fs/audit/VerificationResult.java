package com.aegisos.fs.audit;

import java.util.Collections;
import java.util.List;

/**
 * The result of verifying a single divergence.
 *
 * Contains the enum-typed status, the scan ID against which verification
 * was performed, and the evidence chain (scan IDs) that justified it.
 *
 * Part of the Verification + Recommendation pipeline (Sprint 4).
 */
public final class VerificationResult {

    private final String chunkId;
    private final VerificationStatus status;
    private final String details;
    private final long scanIdVerifiedAgainst;
    private final List<Long> evidenceScans;

    public VerificationResult(String chunkId, VerificationStatus status, String details,
                              long scanIdVerifiedAgainst, List<Long> evidenceScans) {
        this.chunkId = chunkId;
        this.status = status;
        this.details = details;
        this.scanIdVerifiedAgainst = scanIdVerifiedAgainst;
        this.evidenceScans = evidenceScans != null
                ? Collections.unmodifiableList(evidenceScans)
                : Collections.emptyList();
    }

    public String chunkId() { return chunkId; }
    public VerificationStatus status() { return status; }
    public String details() { return details; }
    public long scanIdVerifiedAgainst() { return scanIdVerifiedAgainst; }
    public List<Long> evidenceScans() { return evidenceScans; }
}

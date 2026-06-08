package com.aegisos.fs.audit;

import java.util.Collections;
import java.util.List;

/**
 * Immutable record of a single storage audit scan.
 *
 * Part of the Verification + Recommendation pipeline (Sprint 4).
 */
public final class AuditReport {

    private final long scanId;
    private final long timestamp;
    private final List<DivergenceReportGenerator.UnderReplicatedChunk> divergences;

    public AuditReport(long scanId, long timestamp,
                       List<DivergenceReportGenerator.UnderReplicatedChunk> divergences) {
        this.scanId = scanId;
        this.timestamp = timestamp;
        this.divergences = Collections.unmodifiableList(divergences);
    }

    public long scanId() { return scanId; }
    public long timestamp() { return timestamp; }
    public List<DivergenceReportGenerator.UnderReplicatedChunk> divergences() { return divergences; }

    /**
     * Returns true if this report contains a divergence for the given chunk.
     */
    public boolean containsChunk(String chunkIdHex) {
        for (DivergenceReportGenerator.UnderReplicatedChunk d : divergences) {
            if (d.chunkIdHex.equals(chunkIdHex)) {
                return true;
            }
        }
        return false;
    }
}

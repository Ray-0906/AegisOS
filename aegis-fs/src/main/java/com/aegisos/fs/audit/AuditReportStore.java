package com.aegisos.fs.audit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * In-memory sliding window of audit scan history.
 *
 * Retains the last {@value #MAX_SCANS} complete {@link AuditReport} scans.
 * Used by {@link StorageVerifier} to check whether a divergence has persisted
 * across consecutive scans.
 *
 * Part of the Verification + Recommendation pipeline (Sprint 4).
 */
public final class AuditReportStore {

    static final int MAX_SCANS = 20;

    private final Deque<AuditReport> history = new ArrayDeque<>();

    /**
     * Adds a new report. Evicts the oldest if at capacity.
     */
    public synchronized void addReport(AuditReport report) {
        if (history.size() >= MAX_SCANS) {
            history.removeFirst();
        }
        history.addLast(report);
    }

    /**
     * Returns true if the chunk has appeared in the divergence list of
     * the last {@code consecutiveScansRequired} consecutive scans.
     *
     * Walks history from the tail (most recent) backwards.
     */
    public synchronized boolean hasPersisted(String chunkIdHex, int consecutiveScansRequired) {
        if (consecutiveScansRequired <= 0) return true;
        if (history.size() < consecutiveScansRequired) return false;

        Iterator<AuditReport> it = history.descendingIterator();
        int consecutiveCount = 0;

        while (it.hasNext() && consecutiveCount < consecutiveScansRequired) {
            AuditReport report = it.next();
            if (report.containsChunk(chunkIdHex)) {
                consecutiveCount++;
            } else {
                break; // gap breaks the chain
            }
        }

        return consecutiveCount >= consecutiveScansRequired;
    }

    /**
     * Returns the scan IDs that form the evidence chain for a chunk,
     * if it has persisted for at least {@code consecutiveScansRequired}
     * consecutive scans. Returns an empty list if not persisted.
     */
    public synchronized List<Long> evidenceScansFor(String chunkIdHex, int consecutiveScansRequired) {
        if (!hasPersisted(chunkIdHex, consecutiveScansRequired)) {
            return Collections.emptyList();
        }

        List<Long> evidence = new ArrayList<>();
        Iterator<AuditReport> it = history.descendingIterator();
        int count = 0;

        while (it.hasNext() && count < consecutiveScansRequired) {
            AuditReport report = it.next();
            if (report.containsChunk(chunkIdHex)) {
                evidence.add(report.scanId());
                count++;
            } else {
                break;
            }
        }

        // Return in chronological order (oldest first)
        Collections.reverse(evidence);
        return evidence;
    }

    /**
     * Returns an unmodifiable view of the scan history (oldest first).
     */
    public synchronized List<AuditReport> recentScans() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    /**
     * Returns the number of scans currently in the store.
     */
    public synchronized int size() {
        return history.size();
    }

    /**
     * Clears all historical scans from the store.
     */
    public synchronized void clear() {
        history.clear();
    }
}

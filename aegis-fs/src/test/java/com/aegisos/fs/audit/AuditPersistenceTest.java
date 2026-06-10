package com.aegisos.fs.audit;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AuditReportStore}.
 *
 * Verifies:
 * - hasPersisted returns false with only 1 scan
 * - hasPersisted returns true after 2 consecutive scans
 * - Gap in the middle breaks persistence
 * - evidenceScansFor returns correct scan IDs
 * - History is capped at 20
 */
public class AuditPersistenceTest {

    private final AuditReportStore store = new AuditReportStore();

    private AuditReport reportWithChunk(long scanId, String chunkId) {
        DivergenceReportGenerator.UnderReplicatedChunk chunk =
                new DivergenceReportGenerator.UnderReplicatedChunk(chunkId, 3, 2, Collections.emptyList());
        return new AuditReport(scanId, System.currentTimeMillis(), List.of(chunk));
    }

    private AuditReport emptyReport(long scanId) {
        return new AuditReport(scanId, System.currentTimeMillis(), Collections.emptyList());
    }

    @Test
    void testSingleScanInsufficientHistory() {
        store.addReport(reportWithChunk(1, "abc123"));

        assertFalse(store.hasPersisted("abc123", 2),
                "One scan is not enough for 2-scan persistence");
        assertTrue(store.evidenceScansFor("abc123", 2).isEmpty(),
                "No evidence chain when persistence check fails");
    }

    @Test
    void testTwoConsecutiveScansVerifies() {
        store.addReport(reportWithChunk(1, "abc123"));
        store.addReport(reportWithChunk(2, "abc123"));

        assertTrue(store.hasPersisted("abc123", 2),
                "Two consecutive scans should satisfy 2-scan persistence");

        List<Long> evidence = store.evidenceScansFor("abc123", 2);
        assertEquals(2, evidence.size());
        assertEquals(1L, evidence.get(0), "Oldest scan first");
        assertEquals(2L, evidence.get(1), "Most recent scan last");
    }

    @Test
    void testGapBreaksPersistence() {
        store.addReport(reportWithChunk(1, "abc123"));
        store.addReport(emptyReport(2));  // gap
        store.addReport(reportWithChunk(3, "abc123"));

        assertFalse(store.hasPersisted("abc123", 2),
                "Gap between scan 1 and scan 3 should break persistence");
        assertTrue(store.evidenceScansFor("abc123", 2).isEmpty());
    }

    @Test
    void testGapFollowedByTwoConsecutive() {
        store.addReport(reportWithChunk(1, "abc123"));
        store.addReport(emptyReport(2));  // gap
        store.addReport(reportWithChunk(3, "abc123"));
        store.addReport(reportWithChunk(4, "abc123"));

        assertTrue(store.hasPersisted("abc123", 2),
                "Scans 3 and 4 are consecutive");

        List<Long> evidence = store.evidenceScansFor("abc123", 2);
        assertEquals(2, evidence.size());
        assertEquals(3L, evidence.get(0));
        assertEquals(4L, evidence.get(1));
    }

    @Test
    void testMultipleChunksIndependent() {
        store.addReport(new AuditReport(1, System.currentTimeMillis(), List.of(
                new DivergenceReportGenerator.UnderReplicatedChunk("aaa", 3, 2, Collections.emptyList()),
                new DivergenceReportGenerator.UnderReplicatedChunk("bbb", 3, 1, Collections.emptyList())
        )));
        store.addReport(new AuditReport(2, System.currentTimeMillis(), List.of(
                new DivergenceReportGenerator.UnderReplicatedChunk("aaa", 3, 2, Collections.emptyList())
                // bbb missing from this scan
        )));

        assertTrue(store.hasPersisted("aaa", 2));
        assertFalse(store.hasPersisted("bbb", 2),
                "bbb only appeared in scan 1, not scan 2");
    }

    @Test
    void testHistoryCappedAt20() {
        for (int i = 1; i <= 25; i++) {
            store.addReport(reportWithChunk(i, "abc123"));
        }

        assertEquals(AuditReportStore.MAX_SCANS, store.size(),
                "History should be capped at " + AuditReportStore.MAX_SCANS);

        List<AuditReport> scans = store.recentScans();
        assertEquals(6L, scans.get(0).scanId(),
                "Oldest retained scan should be 6 (scans 1-5 evicted)");
        assertEquals(25L, scans.get(scans.size() - 1).scanId(),
                "Most recent scan should be 25");
    }

    @Test
    void testUnknownChunkNeverPersists() {
        store.addReport(reportWithChunk(1, "abc123"));
        store.addReport(reportWithChunk(2, "abc123"));

        assertFalse(store.hasPersisted("unknown", 2));
        assertFalse(store.hasPersisted("unknown", 1));
    }

    @Test
    void testRequiredScansZeroAlwaysTrue() {
        assertTrue(store.hasPersisted("anything", 0),
                "0 required scans is trivially satisfied");
    }

    @Test
    void testEvidenceScansChronologicalOrder() {
        store.addReport(reportWithChunk(10, "abc"));
        store.addReport(reportWithChunk(20, "abc"));
        store.addReport(reportWithChunk(30, "abc"));

        List<Long> evidence = store.evidenceScansFor("abc", 3);
        assertEquals(List.of(10L, 20L, 30L), evidence,
                "Evidence scans should be in chronological order");
    }
}

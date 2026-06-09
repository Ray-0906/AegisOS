package com.aegisos.cluster;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 6 acceptance test: InstallSnapshot RPC for lagging nodes.
 *
 * Phase 1: Leader has snapshot + truncated log
 * Phase 2: New node joins (needs entries before truncation point)
 * Phase 3: Leader sends InstallSnapshot RPC
 * Phase 4: New node catches up via snapshot + remaining log entries
 * Phase 5: New node has full state (voter set, files, artifacts, repair tasks)
 */
@Disabled("Sprint 6 — not yet implemented")
public class SnapshotTransferTest {

    @Test
    @DisplayName("Lagging node catches up via InstallSnapshot RPC")
    void snapshotTransfer() {
        // TODO: Sprint 6 implementation
    }
}

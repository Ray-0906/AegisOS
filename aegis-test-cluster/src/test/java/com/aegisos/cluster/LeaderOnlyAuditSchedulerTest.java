package com.aegisos.cluster;

import com.aegisos.core.util.HexUtil;
import com.aegisos.fs.audit.*;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that the StorageAuditScheduler respects leadership semantics.
 * Only the consensus leader should run audits and generate recommendations.
 */
public class LeaderOnlyAuditSchedulerTest {

    private final ClusterHarness harness = new ClusterHarness();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void testOnlyLeaderProducesAuditsAndRecommendations() throws Exception {
        List<AegisNode> nodes = harness.start(3);
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> n.discovery().membership().alivePeerIds().size() == 2));

        AegisNode leader = findLeader(nodes);

        // Write file to induce replication
        leader.fileSystem().write("test-leadership.txt", "data".getBytes());
        ClusterHarness.await(5000, () ->
                nodes.stream().allMatch(n -> !n.fileSystem().chunkStore().listChunkIds().isEmpty()));

        String chunkIdHex = nodes.get(0).fileSystem().chunkStore().listChunkIds().get(0);

        // Find a follower and delete the chunk replica
        AegisNode follower = nodes.stream()
                .filter(n -> !n.identity().nodeId().equals(leader.identity().nodeId()))
                .findFirst().get();
        follower.fileSystem().chunkStore().delete(HexUtil.decode(chunkIdHex));

        // 1. Run audit on a follower
        StorageAuditScheduler followerScheduler = follower.auditScheduler();
        followerScheduler.runOnce();

        // Follower should bypass audit, and have empty verifications/recommendations
        assertTrue(followerScheduler.getVerifications().isEmpty(),
                "Follower should not produce verifications");
        assertTrue(followerScheduler.getRecommendations().isEmpty(),
                "Follower should not produce recommendations");
        assertTrue(followerScheduler.getStore().recentScans().isEmpty(),
                "Follower should not store audit reports");

        // 2. Run audit on leader
        StorageAuditScheduler leaderScheduler = leader.auditScheduler();
        leaderScheduler.runOnce();

        // Leader should produce verification
        assertFalse(leaderScheduler.getVerifications().isEmpty(),
                "Leader must produce verifications");
        assertEquals(VerificationStatus.INSUFFICIENT_HISTORY,
                leaderScheduler.getVerifications().get(0).status());
        assertFalse(leaderScheduler.getStore().recentScans().isEmpty(),
                "Leader must record audit reports in history");

        // 3. Kill leader, trigger reelection
        String oldLeaderId = leader.identity().nodeId().toString();
        leader.close();

        // Wait for reelection
        AegisNode newLeader = null;
        for (int attempt = 0; attempt < 200; attempt++) {
            for (AegisNode n : nodes) {
                if (n.consensus().isLeader() && !n.identity().nodeId().toString().equals(oldLeaderId)) {
                    newLeader = n;
                    break;
                }
            }
            if (newLeader != null) break;
            Thread.sleep(50);
        }
        assertNotNull(newLeader, "New leader must be elected");

        // Wait for the new leader to catch up its state machine (commit pending entries)
        final AegisNode finalNewLeader = newLeader;
        ClusterHarness.await(5000, () -> !finalNewLeader.fileSystem().fileIndex().all().isEmpty());

        // The old leader is dead, the new leader should now run the audit successfully
        StorageAuditScheduler newLeaderScheduler = newLeader.auditScheduler();
        newLeaderScheduler.runOnce();

        // Since it's a new leader and it had empty history (was follower before),
        // the first scan on new leader returns INSUFFICIENT_HISTORY
        assertFalse(newLeaderScheduler.getVerifications().isEmpty(),
                "New leader must produce verifications");
        assertEquals(VerificationStatus.INSUFFICIENT_HISTORY,
                newLeaderScheduler.getVerifications().get(0).status());
    }

    private AegisNode findLeader(List<AegisNode> nodes) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            for (AegisNode n : nodes) {
                if (n.consensus().isLeader()) return n;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("No leader found");
    }
}

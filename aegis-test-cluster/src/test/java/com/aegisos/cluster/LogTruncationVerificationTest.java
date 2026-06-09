package com.aegisos.cluster;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

public class LogTruncationVerificationTest {

    @Test
    @DisplayName("Verify snapshots actively shrink the raft log and advance snapshotIndex")
    void verifyTrueCompactionOccurs() throws Exception {
        try (ClusterHarness cluster = new ClusterHarness()) {
            List<com.aegisos.node.AegisNode> nodes = cluster.start(3);
            com.aegisos.node.AegisNode leader = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                for (com.aegisos.node.AegisNode n : nodes) if (n.consensus().isLeader()) leader = n;
                if (leader != null) break;
                Thread.sleep(50);
            }
            Assertions.assertNotNull(leader);

            // Upload 100 files to generate >100 log entries
            for (int i = 0; i < 100; i++) {
                leader.fileSystem().write("file-" + i, new byte[10]);
            }
            
            // Wait for replication
            Thread.sleep(2000);

            long beforeEntries = leader.consensus().raftNode().raftLog().entryCount();
            long beforeIndex = leader.consensus().raftNode().raftLog().snapshotIndex();
            
            Assertions.assertTrue(beforeEntries >= 100, "Log should have at least 100 entries before snapshot");
            
            // Trigger snapshot
            leader.consensus().raftNode().triggerSnapshot();
            Thread.sleep(1000); // Give IO time to settle

            long afterEntries = leader.consensus().raftNode().raftLog().entryCount();
            long afterIndex = leader.consensus().raftNode().raftLog().snapshotIndex();

            Assertions.assertTrue(afterIndex >= 100, "Snapshot index should have advanced beyond 100");
            Assertions.assertTrue(afterEntries <= 5, "Log must be truncated to leave only recent/tail entries");
            Assertions.assertTrue(leader.consensus().raftNode().snapshotCreatedCount() > 0, "Snapshot metrics should increment");

            // Verify a restart loads the snapshot and we don't replay 100+ entries
            cluster.stop(leader);
            com.aegisos.node.AegisNode restartedLeader = cluster.restartNode(leader);

            ClusterHarness.await(5000, () -> restartedLeader.fileSystem().fileIndex().byName("file-99").isPresent());
            
            Assertions.assertEquals(afterIndex, restartedLeader.consensus().raftNode().raftLog().snapshotIndex(), 
                "Restarted node should load the snapshot metadata correctly");
            Assertions.assertTrue(restartedLeader.consensus().raftNode().lastApplied() >= afterIndex,
                "Node should start replay from the snapshot point, avoiding full log replay");
        }
    }
}

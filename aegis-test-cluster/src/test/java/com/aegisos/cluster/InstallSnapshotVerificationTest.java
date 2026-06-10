package com.aegisos.cluster;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

public class InstallSnapshotVerificationTest {

    @Test
    @DisplayName("Verify a lagging follower strictly catches up via InstallSnapshot when leader's log is truncated")
    void verifyStrictInstallSnapshotUsage() throws Exception {
        try (ClusterHarness cluster = new ClusterHarness()) {
            cluster.setReplicationFactor(1);
            List<com.aegisos.node.AegisNode> nodes = cluster.start(5);
            com.aegisos.node.AegisNode leader = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                for (com.aegisos.node.AegisNode n : nodes) if (n.consensus().isLeader()) leader = n;
                if (leader != null) break;
                Thread.sleep(50);
            }
            Assertions.assertNotNull(leader);

            // 1. Stop a follower
            final com.aegisos.node.AegisNode finalLeader = leader;
            com.aegisos.node.AegisNode follower = nodes.stream()
                    .filter(n -> !n.identity().nodeId().equals(finalLeader.identity().nodeId()))
                    .findFirst().get();

            cluster.stop(follower);

            // 2. Advance the leader significantly while the follower is offline
            for (int i = 0; i < 50; i++) {
                com.aegisos.proto.JobRecord job = com.aegisos.proto.JobRecord.newBuilder()
                        .setSpec(com.aegisos.proto.JobSpec.newBuilder().setJobId("missing-job-" + i).build())
                        .setState(com.aegisos.proto.JobState.QUEUED)
                        .build();
                com.aegisos.proto.StateCommand cmd = com.aegisos.proto.StateCommand.newBuilder()
                        .setType(com.aegisos.proto.CommandType.ASSIGN_JOB)
                        .setPayload(job.toByteString())
                        .build();
                leader.consensus().propose(cmd);
            }
            Thread.sleep(1000);

            // 3. Trigger a snapshot on the leader to truncate the log
            leader.consensus().raftNode().triggerSnapshot();
            Thread.sleep(1000); // Allow IO to flush

            Assertions.assertTrue(leader.consensus().raftNode().snapshotCreatedCount() > 0, "Leader must have created a snapshot");
            
            // At this point, leader.log.baseIndex() is advanced. 
            // The offline follower needs the missing files, but the leader's log doesn't have them anymore.

            int initialReceivedCount = follower.consensus().raftNode().installSnapshotReceivedCount();

            // 4. Restart the follower
            com.aegisos.node.AegisNode restartedFollower = cluster.restartNode(follower);

            // 5. Wait for the follower to catch up
            ClusterHarness.await(10000, () -> restartedFollower.runtimeAgent().registry().get("missing-job-49").isPresent());

            // 6. VERIFY STRICTLY THAT INSTALL_SNAPSHOT WAS USED
            int finalReceivedCount = restartedFollower.consensus().raftNode().installSnapshotReceivedCount();
            
            Assertions.assertTrue(finalReceivedCount > initialReceivedCount, 
                "Follower MUST have caught up via InstallSnapshot, not log replay! Received count: " + finalReceivedCount);
                
            int finalSentCount = leader.consensus().raftNode().installSnapshotSentCount();
            Assertions.assertTrue(finalSentCount > 0, 
                "Leader MUST have sent an InstallSnapshot RPC! Sent count: " + finalSentCount);
        }
    }
}

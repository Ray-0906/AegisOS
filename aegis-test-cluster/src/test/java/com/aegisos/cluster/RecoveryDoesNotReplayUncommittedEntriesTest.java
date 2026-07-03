package com.aegisos.cluster;

import com.aegisos.node.AegisNode;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.StateCommand;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class RecoveryDoesNotReplayUncommittedEntriesTest {

    private ClusterHarness harness;

    @BeforeEach
    public void setup() {
        harness = new ClusterHarness();
        // Disable automatic removal of stopped nodes to avoid confounding membership changes
        harness.setAutoRemoveVoters(false);
        harness.setAutoPromoteVoters(false);
    }

    @AfterEach
    public void teardown() {
        harness.close();
    }

    @Test
    public void testRecoveryDoesNotReplayUncommittedEntries() throws Exception {
        // Start a 3-node cluster
        List<AegisNode> nodes = harness.start(3);
        
        // Wait for leader
        AegisNode leader = null;
        for (int i = 0; i < 50; i++) {
            for (AegisNode n : nodes) {
                if (n.consensus().isLeader()) {
                    leader = n;
                    break;
                }
            }
            if (leader != null) break;
            Thread.sleep(100);
        }
        assertTrue(leader != null, "Cluster should have a leader");

        // Stop the followers so the leader cannot achieve quorum
        for (AegisNode n : nodes) {
            if (n != leader) {
                harness.stop(n);
            }
        }

        // Quickly submit a command directly to the leader's RaftNode.
        // It will append to disk but fail to commit due to lack of quorum.
        StateCommand cmd = StateCommand.newBuilder()
                .setType(CommandType.ADD_VOTER)
                .setPayload(ByteString.copyFromUtf8("dummy-node-id"))
                .build();

        long preIndex = leader.consensus().raftNode().lastLogIndex();
        
        try {
            leader.consensus().raftNode().submit(cmd.toByteArray());
        } catch (Exception e) {
            // Might throw NotLeaderException if it stepped down too quickly, but
            // usually there's a small window before election timeout expires.
        }

        long postIndex = leader.consensus().raftNode().lastLogIndex();
        assertTrue(postIndex > preIndex, "Leader should have appended the uncommitted entry to its log");
        
        long commitIndexBeforeCrash = leader.consensus().raftNode().commitIndex();
        assertTrue(commitIndexBeforeCrash < postIndex, "Entry should NOT be committed");

        // Stop the leader (simulate crash)
        harness.stop(leader);

        // Restart the leader node
        AegisNode restartedNode = harness.restartNode(leader);

        // Wait a brief moment for startup to complete
        Thread.sleep(500);

        long lastAppliedAfterRestart = restartedNode.consensus().raftNode().lastApplied();
        long commitIndexAfterRestart = restartedNode.consensus().raftNode().commitIndex();

        // The bug: AegisOS current implementation blindly replays everything on disk
        // We assert that the uncommitted entry was NOT applied.
        // If the bug exists, lastAppliedAfterRestart will be == postIndex, failing the test.
        assertTrue(lastAppliedAfterRestart <= commitIndexAfterRestart, 
            "lastApplied (" + lastAppliedAfterRestart + ") should not exceed commitIndex (" + commitIndexAfterRestart + ")");
            
        assertTrue(lastAppliedAfterRestart < postIndex, 
            "Uncommitted entry at index " + postIndex + " should NOT have been applied, but lastApplied is " + lastAppliedAfterRestart);
    }
}

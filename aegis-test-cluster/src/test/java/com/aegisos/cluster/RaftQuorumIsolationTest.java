package com.aegisos.cluster;

import com.aegisos.node.AegisNode;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.KvPut;
import com.aegisos.proto.StateCommand;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

public class RaftQuorumIsolationTest {

    @Test
    void testQuorumWithDeadNode() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            var nodes = harness.start(3);
            
            // Wait for leader
            AegisNode leader = null;
            for (int i = 0; i < 100; i++) {
                for (AegisNode n : nodes) {
                    if (n.consensus().isLeader()) {
                        leader = n;
                        break;
                    }
                }
                if (leader != null) break;
                Thread.sleep(50);
            }
            assertNotNull(leader);

            final AegisNode finalLeader = leader;

            // Kill one node
            AegisNode toKill = nodes.stream().filter(n -> n != finalLeader).findFirst().get();
            harness.stop(toKill);

            // Poll dynamically until Gossip sweeps and marks it DEAD/evict it
            long deadline = System.currentTimeMillis() + 15000;
            while (finalLeader.discovery().membership().alivePeerIds().contains(toKill.identity().nodeId())) {
                if (System.currentTimeMillis() > deadline) {
                    fail("Node was not marked DEAD by Gossip within 15 seconds");
                }
                Thread.sleep(100);
            }

            // Try to write. Quorum is still majority of 3 (which is 2).
            // Since leader and 1 survivor are alive, write should succeed.
            KvPut put = KvPut.newBuilder().setKey("k1").setValue(com.google.protobuf.ByteString.copyFromUtf8("v1")).build();
            StateCommand cmd = StateCommand.newBuilder()
                    .setType(CommandType.KV_PUT)
                    .setPayload(put.toByteString())
                    .build();
            
            finalLeader.consensus().propose(cmd).get(5, TimeUnit.SECONDS);

            // Kill the second node
            AegisNode secondSurvivor = harness.nodes().stream().filter(n -> n != finalLeader).findFirst().get();
            harness.stop(secondSurvivor);

            // Try to write. Only 1 node left. Quorum is majority of 3 (which is 2).
            // Write should fail/timeout.
            KvPut put2 = KvPut.newBuilder().setKey("k2").setValue(com.google.protobuf.ByteString.copyFromUtf8("v2")).build();
            StateCommand cmd2 = StateCommand.newBuilder()
                    .setType(CommandType.KV_PUT)
                    .setPayload(put2.toByteString())
                    .build();

            assertThrows(Exception.class, () -> {
                finalLeader.consensus().propose(cmd2).get(3, TimeUnit.SECONDS);
            });
        }
    }
}

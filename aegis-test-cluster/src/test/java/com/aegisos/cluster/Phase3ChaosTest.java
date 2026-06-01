package com.aegisos.cluster;

import com.aegisos.node.AegisNode;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.KvPut;
import com.aegisos.proto.StateCommand;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 chaos: with a majority intact after a follower is killed, the cluster keeps a
 * leader and continues to commit new entries.
 */
class Phase3ChaosTest {

    @Test
    void clusterMakesProgressAfterFollowerFailure() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(5);
            assertTrue(ClusterHarness.await(20_000, () ->
                            nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 5)),
                    "cluster membership should converge before Raft assertions");

            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().filter(n -> n.consensus().isLeader()).count() == 1));
            AegisNode leader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst().orElseThrow();

            // Kill a follower (majority 3 of 4 remaining still available).
            AegisNode follower = nodes.stream().filter(n -> !n.consensus().isLeader()).findFirst().orElseThrow();
            harness.stop(follower);

            // Re-resolve a leader (the original may step down if it was isolated; it wasn't here).
            assertTrue(ClusterHarness.await(8_000, () ->
                    harness.nodes().stream().filter(n -> n.consensus().isLeader()).count() == 1));
            AegisNode current = harness.nodes().stream()
                    .filter(n -> n.consensus().isLeader()).findFirst().orElseThrow();

            current.consensus().propose(kvPut("survivor", "ok")).get(10, TimeUnit.SECONDS);

            assertTrue(ClusterHarness.await(8_000, () ->
                    harness.nodes().stream().allMatch(n ->
                            n.consensus().stateMachine().kvGet("survivor").isPresent())));
            Optional<byte[]> v = current.consensus().stateMachine().kvGet("survivor");
            assertTrue(v.isPresent());
            assertArrayEquals("ok".getBytes(), v.get());
        }
    }

    private static StateCommand kvPut(String key, String value) {
        KvPut put = KvPut.newBuilder().setKey(key).setValue(ByteString.copyFromUtf8(value)).build();
        return StateCommand.newBuilder().setType(CommandType.KV_PUT).setPayload(put.toByteString()).build();
    }
}

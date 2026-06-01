package com.aegisos.cluster;

import com.aegisos.node.AegisNode;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.KvPut;
import com.aegisos.proto.StateCommand;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 gate: a 3-node cluster elects a single leader, replicates many entries, and
 * recovers (elects a new leader, retains committed data) after the leader is killed.
 */
class Phase3Test {

    @Test
    void electReplicateAndRecover() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                            nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)),
                    "cluster membership should converge before Raft assertions");

            assertTrue(ClusterHarness.await(20_000, () -> exactlyOneLeader(nodes)),
                    "exactly one leader should be elected");

            AegisNode leader = leaderOf(nodes).orElseThrow();

            // Replicate 1000 KV entries through the leader.
            int n = 1000;
            CompletableFuture<?>[] futures = new CompletableFuture[n];
            for (int i = 0; i < n; i++) {
                futures[i] = leader.consensus().propose(kvPut("k" + i, "v" + i));
            }
            CompletableFuture.allOf(futures).get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(leader.consensus().raftNode().commitIndex() >= n,
                    "leader should have committed all entries");

            // Committed data eventually visible on every node's state machine.
            assertTrue(ClusterHarness.await(10_000, () -> nodes.stream().allMatch(node ->
                    node.consensus().stateMachine().kvGet("k999").isPresent())),
                    "all nodes should apply committed entries");

            // Kill the leader; survivors must elect a new leader and keep the data.
            harness.stop(leader);
            List<AegisNode> survivors = nodes.stream().filter(n2 -> n2 != leader).toList();

            assertTrue(ClusterHarness.await(8_000, () -> exactlyOneLeader(survivors)),
                    "a new leader should be elected after failure");
            AegisNode newLeader = leaderOf(survivors).orElseThrow();
            Optional<byte[]> value = newLeader.consensus().stateMachine().kvGet("k500");
            assertTrue(value.isPresent(), "committed data must survive leader failure");
            assertArrayEquals("v500".getBytes(), value.get());
        }
    }

    private static StateCommand kvPut(String key, String value) {
        KvPut put = KvPut.newBuilder()
                .setKey(key)
                .setValue(ByteString.copyFromUtf8(value))
                .build();
        return StateCommand.newBuilder()
                .setType(CommandType.KV_PUT)
                .setPayload(put.toByteString())
                .build();
    }

    private static boolean exactlyOneLeader(List<AegisNode> nodes) {
        return nodes.stream().filter(n -> n.consensus().isLeader()).count() == 1;
    }

    private static Optional<AegisNode> leaderOf(List<AegisNode> nodes) {
        return nodes.stream().filter(n -> n.consensus().isLeader()).findFirst();
    }
}

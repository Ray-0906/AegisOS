package com.aegisos.cluster;

import com.aegisos.core.identity.NodeId;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.ChunkRef;
import com.aegisos.proto.FileMetadata;
import com.aegisos.proto.PeerStatus;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 4 gate: a file written from one node is fully retrievable from another, and a
 * file with replication factor 3 survives a replica-holder crash via self-healing.
 */
class Phase4Test {

    @Test
    void writeReadAndSelfHeal() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(4);

            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 4)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())),
                    "cluster of 4 should converge and elect a leader");

            AegisNode a = nodes.get(0);
            AegisNode b = nodes.get(1);
            String name = "/cluster/big.bin";

            byte[] data = new byte[10 * 1024 * 1024]; // 10 MiB
            new Random(7).nextBytes(data);
            a.fileSystem().write(name, data);

            // The file metadata replicates to B; then B can read the whole file.
            assertTrue(ClusterHarness.await(10_000, () ->
                    b.fileSystem().fileIndex().byName(name).isPresent()),
                    "file metadata should replicate to node B");
            byte[] readBack = b.fileSystem().read(name);
            assertArrayEquals(data, readBack, "file read from B must match what A wrote");

            // Pick a chunk holder that is not the current leader, then kill it.
            FileMetadata meta = b.fileSystem().fileIndex().byName(name).orElseThrow();
            ChunkRef firstChunk = meta.getChunks(0);
            NodeId victimId = pickNonLeaderHolder(nodes, firstChunk);
            AegisNode victim = nodes.stream()
                    .filter(n -> n.identity().nodeId().equals(victimId)).findFirst().orElseThrow();
            harness.stop(victim);

            // Self-healing should restore the replication factor for that chunk within ~60s.
            assertTrue(ClusterHarness.await(40_000, () -> {
                Optional<FileMetadata> m = harness.nodes().get(0).fileSystem().fileIndex().byName(name);
                if (m.isEmpty()) {
                    return false;
                }
                ChunkRef chunk0 = m.get().getChunks(0);
                long aliveHolders = chunk0.getNodeIdsList().stream()
                        .map(bs -> NodeId.of(bs.toByteArray()))
                        .filter(id -> isAliveHolder(harness.nodes().get(0), id))
                        .count();
                return aliveHolders >= 3;
            }), "chunk should be re-replicated back to 3 healthy holders");

            // File remains fully readable after the failure + healing.
            byte[] afterHeal = harness.nodes().get(0).fileSystem().read(name);
            assertArrayEquals(data, afterHeal, "file must survive the node failure");
        }
    }

    private static boolean isAliveHolder(AegisNode observer, NodeId id) {
        if (id.equals(observer.identity().nodeId())) {
            return true;
        }
        return observer.discovery().membership().statusOf(id) == PeerStatus.ALIVE;
    }

    private static NodeId pickNonLeaderHolder(List<AegisNode> nodes, ChunkRef chunk) {
        NodeId leader = nodes.stream().filter(n -> n.consensus().isLeader())
                .map(n -> n.identity().nodeId()).findFirst().orElse(null);
        for (ByteString bs : chunk.getNodeIdsList()) {
            NodeId id = NodeId.of(bs.toByteArray());
            if (!id.equals(leader)) {
                return id;
            }
        }
        return NodeId.of(chunk.getNodeIds(0).toByteArray());
    }
}

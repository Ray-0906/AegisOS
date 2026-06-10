package com.aegisos.cluster;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.util.HexUtil;
import com.aegisos.fs.audit.ObservedStateCollector;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ObservedStateCollectorRemoteTest {

    private final ClusterHarness harness = new ClusterHarness();

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void testRemoteObservation() throws Exception {
        List<AegisNode> nodes = harness.start(3);
        ClusterHarness.await(5000, () -> nodes.stream().allMatch(n -> n.discovery().membership().alivePeerIds().size() == 2));

        AegisNode leader = nodes.stream().filter(n -> n.consensus().isLeader()).findFirst()
                .orElseThrow(() -> new RuntimeException("No leader elected"));
        AegisNode follower = nodes.stream().filter(n -> !n.consensus().isLeader()).findFirst().get();

        // Write a test chunk physically into the follower's chunk store
        byte[] chunkId = new byte[32];
        chunkId[0] = (byte) 0xFF;
        String chunkHex = HexUtil.encode(chunkId);
        follower.fileSystem().chunkStore().put(chunkId, new byte[]{1, 2, 3});

        // Run the collector from the leader
        ObservedStateCollector collector = new ObservedStateCollector();
        Map<NodeId, Set<String>> observedState = collector.observeRemoteState(
                leader.network(),
                leader.discovery().membership(),
                leader.identity().nodeId(),
                leader.fileSystem().chunkStore()
        );

        // Leader's state should be observed as empty
        assertEquals(3, observedState.size(), "Should have observed all 3 nodes");
        assertTrue(observedState.get(leader.identity().nodeId()).isEmpty(), "Leader should have no chunks");

        // Follower's state should contain the manually inserted chunk
        Set<String> followerChunks = observedState.get(follower.identity().nodeId());
        assertTrue(followerChunks.contains(chunkHex), "Collector failed to observe chunk remotely over TCP");
    }
}

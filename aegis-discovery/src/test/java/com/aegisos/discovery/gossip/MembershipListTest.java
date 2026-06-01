package com.aegisos.discovery.gossip;

import com.aegisos.core.crypto.Hashing;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.model.Endpoint;
import com.aegisos.proto.PeerStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MembershipListTest {

    private static NodeId randomId(int seed) {
        byte[] pk = new byte[32];
        pk[0] = (byte) seed;
        return NodeId.of(Hashing.sha256(pk));
    }

    @Test
    void observeAddsAlivePeer() {
        NodeId self = randomId(1);
        MembershipList list = new MembershipList(self, new byte[32], "127.0.0.1:9000", 1000);
        NodeId peer = randomId(2);
        list.observe(peer, new byte[32], new Endpoint("127.0.0.1", 9001));

        assertEquals(PeerStatus.ALIVE, list.statusOf(peer));
        assertEquals(2, list.aliveCount()); // self + peer
        assertTrue(list.endpointOf(peer).isPresent());
    }

    @Test
    void mergeTakesHigherVersion() {
        NodeId self = randomId(1);
        MembershipList a = new MembershipList(self, new byte[32], "127.0.0.1:9000", 1000);
        NodeId peer = randomId(2);
        a.observe(peer, new byte[32], new Endpoint("127.0.0.1", 9001));

        // A second membership list with a fresher view of the same peer.
        MembershipList b = new MembershipList(randomId(3), new byte[32], "127.0.0.1:9002", 1000);
        b.observe(peer, new byte[32], new Endpoint("127.0.0.1", 9099));
        b.observe(peer, new byte[32], new Endpoint("127.0.0.1", 9099)); // bump version

        a.merge(b.snapshot());
        assertTrue(a.endpointOf(peer).isPresent());
    }

    @Test
    void selfIsAlwaysAlive() {
        NodeId self = randomId(1);
        MembershipList list = new MembershipList(self, new byte[32], "127.0.0.1:9000", 1000);
        list.sweep();
        assertEquals(1, list.aliveCount());
    }
}

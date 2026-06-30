package com.aegisos.cluster;

import com.aegisos.core.identity.IdentityService;
import com.aegisos.core.message.AegisMessage;
import com.aegisos.core.message.MessageType;
import com.aegisos.core.model.Endpoint;
import com.aegisos.network.NetworkLayer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1 gate: Node A establishes a signed, encrypted channel to Node B and exchanges
 * an authenticated message that B verifies and answers.
 */
class Phase1Test {

    @Test
    void twoNodesHandshakeAndExchangeEncryptedMessage() throws Exception {
        IdentityService idA = IdentityService.ephemeral();
        IdentityService idB = IdentityService.ephemeral();

        try (NetworkLayer netA = new NetworkLayer(idA, new com.aegisos.core.security.IdentityManager(java.nio.file.Files.createTempDirectory("test")), 0, "127.0.0.1");
             NetworkLayer netB = new NetworkLayer(idB, new com.aegisos.core.security.IdentityManager(java.nio.file.Files.createTempDirectory("test")), 0, "127.0.0.1")) {

            netB.registerHandler(MessageType.PING, req -> {
                // Echo the payload back as a PONG over the encrypted channel.
                return new AegisMessage(null, req.sender(), MessageType.PONG, req.payload());
            });

            netA.start();
            netB.start();

            var bNodeId = netA.connect(new Endpoint("127.0.0.1", netB.boundPort()));
            assertEquals(idB.nodeId(), bNodeId, "A must learn B's verified node id from the handshake");
            assertTrue(idA.trustStore().isKnown(idB.nodeId()), "A must pin B's key (TOFU)");
            assertTrue(idB.trustStore().isKnown(idA.nodeId()), "B must pin A's key (TOFU)");

            byte[] ping = "ping-payload".getBytes(StandardCharsets.UTF_8);
            AegisMessage pong = netA.request(bNodeId, MessageType.PING, ping)
                    .get(5, TimeUnit.SECONDS);

            assertEquals(MessageType.PONG, pong.type());
            assertArrayEquals(ping, pong.payload(), "encrypted round-trip payload must match");
        }
    }
}

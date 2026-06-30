package com.aegisos.network;

import com.aegisos.core.identity.IdentityService;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.model.Endpoint;
import com.aegisos.core.message.AegisMessage;
import com.aegisos.core.message.MessageType;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

public class RpcCorrelationIsolationTest {

    @Test
    public void testRequestDoesNotSatisfyFuture() throws Exception {
        IdentityService id1 = IdentityService.ephemeral();
        IdentityService id2 = IdentityService.ephemeral();

        id1.trustStore().pin(id2.nodeId(), id2.publicKey());
        id2.trustStore().pin(id1.nodeId(), id1.publicKey());

        try (NetworkLayer net1 = new NetworkLayer(id1, new com.aegisos.core.security.IdentityManager(java.nio.file.Files.createTempDirectory("test")), 0, "localhost");
             NetworkLayer net2 = new NetworkLayer(id2, new com.aegisos.core.security.IdentityManager(java.nio.file.Files.createTempDirectory("test")), 0, "localhost")) {

            net1.start();
            net2.start();

            net1.connect(new Endpoint("localhost", net2.boundPort()));

            // Wait for connection to be fully established in both directions
            long start = System.currentTimeMillis();
            while ((!net1.isConnected(id2.nodeId()) || !net2.isConnected(id1.nodeId()))
                    && System.currentTimeMillis() - start < 5000) {
                Thread.sleep(100);
            }
            assertTrue(net1.isConnected(id2.nodeId()));
            assertTrue(net2.isConnected(id1.nodeId()));

            // Register handlers that just swallow the message and return null (no response)
            net1.registerHandler(MessageType.GOSSIP_SYN, msg -> null);
            net2.registerHandler(MessageType.GOSSIP_SYN, msg -> null);

            // Node 1 sends a request (gets correlation=1, is_response=false)
            CompletableFuture<AegisMessage> f1 = net1.request(id2.nodeId(), MessageType.GOSSIP_SYN, new byte[0]);
            
            // Node 2 sends a request (gets correlation=1, is_response=false)
            CompletableFuture<AegisMessage> f2 = net2.request(id1.nodeId(), MessageType.GOSSIP_SYN, new byte[0]);

            // Both sides will receive a message with correlation=1 and is_response=false.
            // If the bug exists, f1 or f2 might complete with the other node's request.
            // With the fix, they will BOTH time out because no responses are ever generated.
            
            assertThrows(TimeoutException.class, () -> f1.get(1, TimeUnit.SECONDS),
                    "Future 1 should time out, not be satisfied by an inbound request");
                    
            assertThrows(TimeoutException.class, () -> f2.get(1, TimeUnit.SECONDS),
                    "Future 2 should time out, not be satisfied by an inbound request");
        }
    }

    @Test
    public void testResponseSatisfiesFuture() throws Exception {
        IdentityService id1 = IdentityService.ephemeral();
        IdentityService id2 = IdentityService.ephemeral();

        id1.trustStore().pin(id2.nodeId(), id2.publicKey());
        id2.trustStore().pin(id1.nodeId(), id1.publicKey());

        try (NetworkLayer net1 = new NetworkLayer(id1, new com.aegisos.core.security.IdentityManager(java.nio.file.Files.createTempDirectory("test")), 0, "localhost");
             NetworkLayer net2 = new NetworkLayer(id2, new com.aegisos.core.security.IdentityManager(java.nio.file.Files.createTempDirectory("test")), 0, "localhost")) {

            net1.start();
            net2.start();

            net1.connect(new Endpoint("localhost", net2.boundPort()));

            // Wait for connection
            long start = System.currentTimeMillis();
            while (!net1.isConnected(id2.nodeId()) && System.currentTimeMillis() - start < 5000) {
                Thread.sleep(100);
            }
            assertTrue(net1.isConnected(id2.nodeId()));

            // Node 2 will respond to GOSSIP_SYN with GOSSIP_ACK
            net2.registerHandler(MessageType.GOSSIP_SYN, msg -> 
                new AegisMessage(id1.nodeId(), id2.nodeId(), MessageType.GOSSIP_ACK, new byte[0])
            );

            // Node 1 requests and should receive the ACK (which has is_response=true)
            CompletableFuture<AegisMessage> f1 = net1.request(id2.nodeId(), MessageType.GOSSIP_SYN, new byte[0]);
            
            AegisMessage response = f1.get(5, TimeUnit.SECONDS);
            assertEquals(MessageType.GOSSIP_ACK, response.type(), "Future should be satisfied by the true response");
        }
    }
}

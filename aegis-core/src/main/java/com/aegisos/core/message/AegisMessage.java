package com.aegisos.core.message;

import com.aegisos.core.identity.NodeId;

/**
 * A decrypted, verified application-level message handed to upper layers.
 * The network layer is responsible for producing/consuming the on-wire
 * {@code Envelope}; everything above the network deals only in {@code AegisMessage}.
 *
 * @param sender    verified sender node id
 * @param recipient intended recipient (may be null for broadcast)
 * @param type      logical message type
 * @param payload   the decrypted protobuf payload bytes for {@code type}
 */
public record AegisMessage(NodeId sender, NodeId recipient, MessageType type, byte[] payload) {

    public static AegisMessage to(NodeId recipient, MessageType type, byte[] payload) {
        return new AegisMessage(null, recipient, type, payload);
    }

    public static AegisMessage broadcast(MessageType type, byte[] payload) {
        return new AegisMessage(null, null, type, payload);
    }
}

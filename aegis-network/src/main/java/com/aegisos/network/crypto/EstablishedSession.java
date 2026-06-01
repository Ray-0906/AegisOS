package com.aegisos.network.crypto;

import com.aegisos.core.identity.NodeId;

/** Result of a successful handshake: the peer identity and the encrypted channel. */
public record EstablishedSession(NodeId remoteNodeId,
                                 byte[] remotePublicKey,
                                 String remoteAddress,
                                 SessionCipher cipher) {
}

package com.aegisos.core.identity;

import com.aegisos.core.crypto.Ed25519;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Issues, stores, and uses this node's cryptographic identity (design section 3.1).
 */
public final class IdentityService {

    private static final Logger log = LoggerFactory.getLogger(IdentityService.class);

    private final NodeIdentity identity;
    private final TrustStore trustStore;

    private IdentityService(NodeIdentity identity, TrustStore trustStore) {
        this.identity = identity;
        this.trustStore = trustStore;
        // Pin our own key so local self-verification works.
        trustStore.pin(identity.nodeId(), identity.publicKey());
    }

    /** Loads the identity from the keystore, generating and persisting one on first boot. */
    public static IdentityService bootstrap(KeyStore keyStore) {
        NodeIdentity id = keyStore.load().orElseGet(() -> {
            log.info("No identity found; generating a new Ed25519 keypair");
            NodeIdentity generated = NodeIdentity.generate();
            keyStore.save(generated);
            return generated;
        });
        log.info("Node identity ready: {}", id.nodeId());
        return new IdentityService(id, new TrustStore());
    }

    public static IdentityService ephemeral() {
        return new IdentityService(NodeIdentity.generate(), new TrustStore());
    }

    public NodeId nodeId() {
        return identity.nodeId();
    }

    public byte[] publicKey() {
        return identity.publicKey();
    }

    public TrustStore trustStore() {
        return trustStore;
    }

    public byte[] sign(byte[] message) {
        return Ed25519.sign(identity.privateKey(), message);
    }

    /** Verifies a signature using the sender's pinned public key. */
    public boolean verify(byte[] message, byte[] signature, NodeId senderId) {
        Optional<byte[]> key = trustStore.publicKeyOf(senderId);
        if (key.isEmpty()) {
            log.warn("Cannot verify message from unknown node {}", senderId.shortId());
            return false;
        }
        return Ed25519.verify(key.get(), message, signature);
    }

    /** Verifies a signature against an explicitly supplied public key (handshake path). */
    public boolean verifyWith(byte[] message, byte[] signature, byte[] publicKey) {
        return Ed25519.verify(publicKey, message, signature);
    }

    public Optional<byte[]> getPublicKey(NodeId nodeId) {
        return trustStore.publicKeyOf(nodeId);
    }
}

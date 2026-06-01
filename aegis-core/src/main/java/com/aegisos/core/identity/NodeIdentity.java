package com.aegisos.core.identity;

import com.aegisos.core.crypto.Ed25519;
import com.aegisos.core.crypto.Hashing;

import java.time.Instant;

/**
 * This node's cryptographic identity. The private key never leaves the node and is
 * never serialized into a network message.
 */
public final class NodeIdentity {

    private final NodeId nodeId;
    private final byte[] publicKey;   // 32 bytes
    private final byte[] privateKey;  // 32-byte ed25519 seed
    private final long createdAt;

    public NodeIdentity(byte[] privateKey, byte[] publicKey, long createdAt) {
        if (privateKey == null || privateKey.length != Ed25519.PRIVATE_BYTES) {
            throw new IllegalArgumentException("bad private key length");
        }
        if (publicKey == null || publicKey.length != Ed25519.PUBLIC_BYTES) {
            throw new IllegalArgumentException("bad public key length");
        }
        this.privateKey = privateKey.clone();
        this.publicKey = publicKey.clone();
        this.nodeId = NodeId.of(Hashing.sha256(this.publicKey));
        this.createdAt = createdAt;
    }

    public static NodeIdentity generate() {
        Ed25519.KeyPair kp = Ed25519.generate();
        return new NodeIdentity(kp.privateKey(), kp.publicKey(), Instant.now().toEpochMilli());
    }

    public NodeId nodeId() {
        return nodeId;
    }

    public byte[] publicKey() {
        return publicKey.clone();
    }

    /** Package-private: only the identity service signs with the raw key. */
    byte[] privateKey() {
        return privateKey;
    }

    public long createdAt() {
        return createdAt;
    }
}

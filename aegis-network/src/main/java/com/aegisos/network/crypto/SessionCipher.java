package com.aegisos.network.crypto;

import com.aegisos.core.crypto.AesGcm;

/**
 * Per-connection AES-256-GCM channel. The session key is derived during the handshake
 * via X25519 ECDH + HKDF. A fresh random 12-byte nonce is used per message and never
 * reused for the lifetime of the key.
 */
public final class SessionCipher {

    private final byte[] sessionKey;

    public SessionCipher(byte[] sessionKey) {
        if (sessionKey == null || sessionKey.length != AesGcm.KEY_BYTES) {
            throw new IllegalArgumentException("session key must be " + AesGcm.KEY_BYTES + " bytes");
        }
        this.sessionKey = sessionKey.clone();
    }

    public byte[] newNonce() {
        return AesGcm.randomNonce();
    }

    /** Encrypts the payload, binding it to the header bytes as associated data. */
    public byte[] encrypt(byte[] nonce, byte[] payload, byte[] aad) {
        return AesGcm.encrypt(sessionKey, nonce, payload, aad);
    }

    public byte[] decrypt(byte[] nonce, byte[] ciphertext, byte[] aad) {
        return AesGcm.decrypt(sessionKey, nonce, ciphertext, aad);
    }
}

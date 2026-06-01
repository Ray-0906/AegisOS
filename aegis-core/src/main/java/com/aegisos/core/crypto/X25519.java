package com.aegisos.core.crypto;

import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.security.SecureRandom;

/**
 * X25519 ephemeral ECDH plus HKDF-SHA256 session key derivation for the handshake.
 */
public final class X25519 {

    public static final int KEY_BYTES = 32;

    private static final SecureRandom RNG = new SecureRandom();

    private X25519() {
    }

    public record EphemeralKeyPair(X25519PrivateKeyParameters privateKey, byte[] publicKey) {
    }

    public static EphemeralKeyPair generate() {
        X25519PrivateKeyParameters priv = new X25519PrivateKeyParameters(RNG);
        return new EphemeralKeyPair(priv, priv.generatePublicKey().getEncoded());
    }

    /** Raw 32-byte shared secret from our private key and the peer's public key. */
    public static byte[] sharedSecret(X25519PrivateKeyParameters ourPrivate, byte[] peerPublic) {
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(ourPrivate);
        byte[] secret = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(new X25519PublicKeyParameters(peerPublic, 0), secret, 0);
        return secret;
    }

    /**
     * Derives a 32-byte AES session key from the ECDH secret. The salt should be a
     * deterministic, order-independent combination of both handshake nonces so both
     * peers derive the same key.
     */
    public static byte[] deriveSessionKey(byte[] sharedSecret, byte[] salt, byte[] info) {
        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(sharedSecret, salt, info));
        byte[] out = new byte[AesGcm.KEY_BYTES];
        hkdf.generateBytes(out, 0, out.length);
        return out;
    }
}

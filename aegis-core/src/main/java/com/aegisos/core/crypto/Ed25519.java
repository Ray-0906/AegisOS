package com.aegisos.core.crypto;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.security.SecureRandom;

/**
 * Ed25519 signatures using raw 32-byte keys (clean wire format).
 */
public final class Ed25519 {

    public static final int PRIVATE_BYTES = 32; // seed
    public static final int PUBLIC_BYTES = 32;
    public static final int SIGNATURE_BYTES = 64;

    private static final SecureRandom RNG = new SecureRandom();

    private Ed25519() {
    }

    public record KeyPair(byte[] privateKey, byte[] publicKey) {
    }

    public static KeyPair generate() {
        Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(RNG);
        Ed25519PublicKeyParameters pub = priv.generatePublicKey();
        return new KeyPair(priv.getEncoded(), pub.getEncoded());
    }

    public static byte[] derivePublic(byte[] privateSeed) {
        Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(privateSeed, 0);
        return priv.generatePublicKey().getEncoded();
    }

    public static byte[] sign(byte[] privateSeed, byte[] message) {
        Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(privateSeed, 0);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, priv);
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        if (publicKey == null || publicKey.length != PUBLIC_BYTES
                || signature == null || signature.length != SIGNATURE_BYTES) {
            return false;
        }
        Ed25519PublicKeyParameters pub = new Ed25519PublicKeyParameters(publicKey, 0);
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, pub);
        verifier.update(message, 0, message.length);
        return verifier.verifySignature(signature);
    }
}

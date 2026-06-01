package com.aegisos.core.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * AES-256-GCM authenticated encryption.
 *
 * <p>Nonces are 12 bytes and MUST never be reused for a given key. Callers either
 * supply a fresh random nonce via {@link #randomNonce()} or a per-message counter.
 */
public final class AesGcm {

    public static final int KEY_BYTES = 32;   // AES-256
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BITS = 128;

    private static final SecureRandom RNG = new SecureRandom();

    private AesGcm() {
    }

    public static byte[] randomKey() {
        byte[] k = new byte[KEY_BYTES];
        RNG.nextBytes(k);
        return k;
    }

    public static byte[] randomNonce() {
        byte[] n = new byte[NONCE_BYTES];
        RNG.nextBytes(n);
        return n;
    }

    /** Encrypts plaintext, authenticating the optional associated data. Output includes the GCM tag. */
    public static byte[] encrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    /** Decrypts and verifies the tag. Throws if authentication fails. */
    public static byte[] decrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            if (aad != null) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decrypt/auth failed", e);
        }
    }
}

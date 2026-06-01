package com.aegisos.fs;

import com.aegisos.core.crypto.AesGcm;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Per-chunk AES-256-GCM encryption (design section 3.5). Each chunk gets a fresh random
 * content key and nonce. The content key is itself wrapped (encrypted) with the cluster
 * key and stored alongside the chunk's metadata.
 */
public final class ChunkCipher {

    private final byte[] clusterKey;

    public ChunkCipher(byte[] clusterKey) {
        this.clusterKey = clusterKey.clone();
    }

    public record EncryptedChunk(byte[] ciphertext, byte[] chunkNonce, byte[] wrappedKey) {
    }

    public EncryptedChunk encrypt(byte[] plaintext) {
        byte[] contentKey = AesGcm.randomKey();
        byte[] chunkNonce = AesGcm.randomNonce();
        byte[] ciphertext = AesGcm.encrypt(contentKey, chunkNonce, plaintext, null);
        byte[] wrappedKey = wrap(contentKey);
        return new EncryptedChunk(ciphertext, chunkNonce, wrappedKey);
    }

    public byte[] decrypt(byte[] ciphertext, byte[] chunkNonce, byte[] wrappedKey) {
        byte[] contentKey = unwrap(wrappedKey);
        return AesGcm.decrypt(contentKey, chunkNonce, ciphertext, null);
    }

    /** wrappedKey layout: [12-byte wrap nonce][AES-GCM(clusterKey, nonce, contentKey)]. */
    private byte[] wrap(byte[] contentKey) {
        byte[] wrapNonce = AesGcm.randomNonce();
        byte[] sealed = AesGcm.encrypt(clusterKey, wrapNonce, contentKey, null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(wrapNonce.length + sealed.length);
        bos.writeBytes(wrapNonce);
        bos.writeBytes(sealed);
        return bos.toByteArray();
    }

    private byte[] unwrap(byte[] wrappedKey) {
        byte[] wrapNonce = Arrays.copyOfRange(wrappedKey, 0, AesGcm.NONCE_BYTES);
        byte[] sealed = Arrays.copyOfRange(wrappedKey, AesGcm.NONCE_BYTES, wrappedKey.length);
        return AesGcm.decrypt(clusterKey, wrapNonce, sealed, null);
    }
}

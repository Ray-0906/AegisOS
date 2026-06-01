package com.aegisos.core.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 helpers used for NodeIDs, chunk IDs, and file IDs. */
public final class Hashing {

    private Hashing() {
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                md.update(p);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

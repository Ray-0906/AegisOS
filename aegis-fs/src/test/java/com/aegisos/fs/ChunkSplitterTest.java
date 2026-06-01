package com.aegisos.fs;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ChunkSplitterTest {

    @Test
    void roundTripVariousSizes() {
        ChunkSplitter splitter = new ChunkSplitter(1024);
        Random rng = new Random(42);
        for (int size : new int[]{0, 1, 1023, 1024, 1025, 4096, 5000}) {
            byte[] data = new byte[size];
            rng.nextBytes(data);
            List<byte[]> chunks = splitter.split(data);
            byte[] rebuilt = splitter.reassemble(chunks);
            assertArrayEquals(data, rebuilt, "round-trip failed for size " + size);
        }
    }

    @Test
    void chunkCountMatchesExpected() {
        ChunkSplitter splitter = new ChunkSplitter(100);
        assertEquals(1, splitter.split(new byte[50]).size());
        assertEquals(1, splitter.split(new byte[100]).size());
        assertEquals(2, splitter.split(new byte[101]).size());
        assertEquals(3, splitter.split(new byte[250]).size());
    }

    @Test
    void encryptionRoundTrip() {
        byte[] clusterKey = com.aegisos.core.crypto.Hashing.sha256("secret".getBytes());
        ChunkCipher cipher = new ChunkCipher(clusterKey);
        byte[] plain = "the quick brown fox".getBytes();
        ChunkCipher.EncryptedChunk enc = cipher.encrypt(plain);
        byte[] back = cipher.decrypt(enc.ciphertext(), enc.chunkNonce(), enc.wrappedKey());
        assertArrayEquals(plain, back);
    }
}

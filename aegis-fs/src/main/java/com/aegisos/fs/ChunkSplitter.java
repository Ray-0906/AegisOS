package com.aegisos.fs;

import java.util.ArrayList;
import java.util.List;

/** Splits a byte array into fixed-size chunks and reassembles them (design section 3.5). */
public final class ChunkSplitter {

    public static final int DEFAULT_CHUNK_SIZE = 1024 * 1024; // 1 MiB

    private final int chunkSize;

    public ChunkSplitter() {
        this(DEFAULT_CHUNK_SIZE);
    }

    public ChunkSplitter(int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunk size must be positive");
        }
        this.chunkSize = chunkSize;
    }

    public List<byte[]> split(byte[] data) {
        List<byte[]> chunks = new ArrayList<>();
        if (data.length == 0) {
            chunks.add(new byte[0]);
            return chunks;
        }
        for (int offset = 0; offset < data.length; offset += chunkSize) {
            int len = Math.min(chunkSize, data.length - offset);
            byte[] chunk = new byte[len];
            System.arraycopy(data, offset, chunk, 0, len);
            chunks.add(chunk);
        }
        return chunks;
    }

    public byte[] reassemble(List<byte[]> chunks) {
        int total = chunks.stream().mapToInt(c -> c.length).sum();
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, out, pos, chunk.length);
            pos += chunk.length;
        }
        return out;
    }
}

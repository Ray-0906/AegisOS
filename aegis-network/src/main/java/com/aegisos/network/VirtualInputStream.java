package com.aegisos.network;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class VirtualInputStream extends InputStream {

    private final BlockingQueue<byte[]> chunkQueue = new LinkedBlockingQueue<>();
    private byte[] currentChunk = null;
    private int chunkPosition = 0;
    private volatile boolean closed = false;

    public void enqueueChunk(byte[] data) {
        if (data == null || data.length == 0) {
            closed = true;
            // Unblock any pending read with an empty array (EOF signal)
            chunkQueue.offer(new byte[0]);
        } else {
            chunkQueue.offer(data);
        }
    }

    @Override
    public int read() throws IOException {
        if (ensureChunk()) {
            return currentChunk[chunkPosition++] & 0xFF;
        }
        return -1; // EOF
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }

        if (!ensureChunk()) {
            return -1;
        }

        int available = currentChunk.length - chunkPosition;
        int toRead = Math.min(len, available);
        System.arraycopy(currentChunk, chunkPosition, b, off, toRead);
        chunkPosition += toRead;
        return toRead;
    }

    private boolean ensureChunk() throws IOException {
        while (currentChunk == null || chunkPosition >= currentChunk.length) {
            if (closed && chunkQueue.isEmpty()) {
                return false;
            }
            try {
                currentChunk = chunkQueue.take();
                chunkPosition = 0;
                if (currentChunk.length == 0) { // EOF signal
                    closed = true;
                    return false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for chunk", e);
            }
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        chunkQueue.offer(new byte[0]); // Unblock any waiting read
    }
}

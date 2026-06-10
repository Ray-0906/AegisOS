package com.aegisos.runtime;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Binary wire envelope for stateful checkpoints (Sprint 8).
 * Ensures future-proof schema evolution and strict execution fencing.
 */
public record CheckpointEnvelope(
    int version,
    long executionId,
    long sequence,
    byte[] payload
) {
    public static final int CURRENT_VERSION = 1;

    public void writeTo(OutputStream out) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);
        dos.writeInt(version);
        dos.writeLong(executionId);
        dos.writeLong(sequence);
        dos.writeInt(payload.length);
        dos.write(payload);
        dos.flush();
    }

    public static CheckpointEnvelope readFrom(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int version = dis.readInt();
        long executionId = dis.readLong();
        long sequence = dis.readLong();
        int payloadLen = dis.readInt();
        byte[] payload = new byte[payloadLen];
        dis.readFully(payload);
        return new CheckpointEnvelope(version, executionId, sequence, payload);
    }

    public byte[] toByteArray() {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            writeTo(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Unreachable", e);
        }
    }

    public static CheckpointEnvelope fromByteArray(byte[] bytes) {
        try {
            return readFrom(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new RuntimeException("Invalid CheckpointEnvelope bytes", e);
        }
    }
}

package com.aegisos.network.wire;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * Length-prefixed framing for protobuf {@code Envelope} messages on a TCP stream.
 * Each frame is a 4-byte big-endian unsigned length followed by that many bytes.
 */
public final class Framing {

    /** Hard cap to avoid OOM from a hostile/garbled length prefix (64 MiB). */
    public static final int MAX_FRAME = 64 * 1024 * 1024;

    private Framing() {
    }

    public static void writeFrame(DataOutputStream out, byte[] payload) throws IOException {
        if (payload.length > MAX_FRAME) {
            throw new IOException("frame too large: " + payload.length);
        }
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    public static byte[] readFrame(DataInputStream in) throws IOException {
        int len;
        try {
            len = in.readInt();
        } catch (EOFException eof) {
            return null; // clean close
        }
        if (len < 0 || len > MAX_FRAME) {
            throw new IOException("invalid frame length: " + len);
        }
        byte[] buf = new byte[len];
        in.readFully(buf);
        return buf;
    }
}

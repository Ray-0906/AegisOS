package com.aegisos.network;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.message.MessageType;
import com.aegisos.proto.IpcChunkProto;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class VirtualOutputStream extends OutputStream {

    private static final int BUFFER_SIZE = 64 * 1024;
    private final NetworkLayer networkLayer;
    private final NodeId targetNodeId;
    private final String processId;
    private final ByteBuffer buffer;

    public VirtualOutputStream(NetworkLayer networkLayer, NodeId targetNodeId, String processId) {
        this.networkLayer = networkLayer;
        this.targetNodeId = targetNodeId;
        this.processId = processId;
        this.buffer = ByteBuffer.allocate(BUFFER_SIZE);
    }

    @Override
    public void write(int b) throws IOException {
        if (!buffer.hasRemaining()) {
            flush();
        }
        buffer.put((byte) b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int remaining = len;
        int currentOff = off;
        while (remaining > 0) {
            if (!buffer.hasRemaining()) {
                flush();
            }
            int toWrite = Math.min(remaining, buffer.remaining());
            buffer.put(b, currentOff, toWrite);
            currentOff += toWrite;
            remaining -= toWrite;
        }
    }

    @Override
    public void flush() throws IOException {
        if (buffer.position() > 0) {
            buffer.flip();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            buffer.clear();

            IpcChunkProto chunk = IpcChunkProto.newBuilder()
                    .setProcessId(processId)
                    .setData(ByteString.copyFrom(data))
                    .build();

            boolean success = networkLayer.sendAsync(targetNodeId, MessageType.IPC_DATA, chunk.toByteArray());
            if (!success) {
                throw new IOException("Failed to send IPC chunk, node unreachable");
            }
        }
    }

    @Override
    public void close() throws IOException {
        flush();
        IpcChunkProto eofChunk = IpcChunkProto.newBuilder()
                .setProcessId(processId)
                .build();
        boolean success = networkLayer.sendAsync(targetNodeId, MessageType.IPC_EOF, eofChunk.toByteArray());
        if (!success) {
            throw new IOException("Failed to send IPC EOF, node unreachable");
        }
    }
}

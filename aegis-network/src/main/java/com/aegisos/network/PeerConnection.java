package com.aegisos.network;

import com.aegisos.core.identity.IdentityService;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.message.AegisMessage;
import com.aegisos.core.message.MessageType;
import com.aegisos.network.crypto.EstablishedSession;
import com.aegisos.network.wire.EnvelopeCodec;
import com.aegisos.network.wire.Framing;
import com.aegisos.network.wire.ReplayGuard;
import com.aegisos.proto.Envelope;
import com.aegisos.proto.MessageHeader;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single established, encrypted, authenticated connection to one peer.
 * Owns the receive loop (on a virtual thread) and the encrypted send path.
 */
public final class PeerConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PeerConnection.class);
    private static final int OUTBOUND_QUEUE_CAPACITY = 256;

    public interface InboundHandler {
        void onMessage(PeerConnection connection, AegisMessage message, long correlation, boolean isResponse);
        void onConnectionClosed(PeerConnection connection);
    }

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final EstablishedSession session;
    private final IdentityService identity;
    private final ReplayGuard replayGuard;
    private final InboundHandler handler;

    private final AtomicLong sequence = new AtomicLong(1);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final BlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
    private volatile boolean running = true;
    private Thread receiveThread;
    private Thread writerThread;

    public PeerConnection(Socket socket, DataInputStream in, DataOutputStream out,
                          EstablishedSession session, IdentityService identity,
                          InboundHandler handler) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.session = session;
        this.identity = identity;
        this.handler = handler;
        this.replayGuard = new ReplayGuard();
    }

    public NodeId remoteNodeId() {
        return session.remoteNodeId();
    }

    public String remoteAddress() {
        return session.remoteAddress();
    }

    public void startReceiving() {
        writerThread = Thread.ofPlatform()
                .daemon()
                .name("aegis-send-" + remoteNodeId().shortId())
                .start(this::writeLoop);
        // Platform daemon thread: receive loops are few (one per peer) and long-lived,
        // and message delivery must not depend on the virtual thread scheduler.
        receiveThread = Thread.ofPlatform()
                .daemon()
                .name("aegis-recv-" + remoteNodeId().shortId())
                .start(this::receiveLoop);
    }

    /** Sends an encrypted, signed application message to the peer. */
    public void send(MessageType type, byte[] payload, long correlation, boolean isResponse) throws IOException {
        if (!running) {
            throw new IOException("connection is closed");
        }
        byte[] nonce = session.cipher().newNonce();
        MessageHeader header = MessageHeader.newBuilder()
                .setSenderId(ByteString.copyFrom(identity.nodeId().toBytes()))
                .setRecipientId(ByteString.copyFrom(remoteNodeId().toBytes()))
                .setMessageType(type.code())
                .setTimestamp(Instant.now().toEpochMilli())
                .setNonce(ByteString.copyFrom(nonce))
                .setSequence(sequence.getAndIncrement())
                .setCorrelation(correlation)
                .setHandshake(false)
                .setIsResponse(isResponse)
                .build();
        byte[] aad = header.toByteArray();
        byte[] cipherText = session.cipher().encrypt(nonce, payload, aad);
        Envelope env = EnvelopeCodec.build(header, cipherText, identity::sign);
        int size = outbound.size();
        if (size > 200) {
            System.out.printf("HIGH_QUEUE timestamp=%d remoteNode=%s messageType=%s queueSize=%d%n",
                    System.currentTimeMillis(), remoteNodeId().shortId(), type, size);
        }
        
        if (!outbound.offer(env.toByteArray())) {
            System.out.printf("QUEUE_FULL remoteNode=%s messageType=%s%n", remoteNodeId().shortId(), type);
            closeQuietly();
            throw new IOException("outbound queue full for " + remoteNodeId().shortId());
        }
    }

    private void writeLoop() {
        try {
            while (running) {
                Framing.writeFrame(out, outbound.take());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            if (running) {
                log.debug("Connection write to {} failed: {}", remoteNodeId().shortId(), e.getMessage());
            }
        } finally {
            closeQuietly();
        }
    }

    private void receiveLoop() {
        try {
            byte[] frame;
            while (running && (frame = Framing.readFrame(in)) != null) {
                handleFrame(frame);
            }
        } catch (IOException e) {
            if (running) {
                log.debug("Connection to {} closed: {}", remoteNodeId().shortId(), e.getMessage());
            }
        } finally {
            closeQuietly();
        }
    }

    private void handleFrame(byte[] frame) {
        try {
            Envelope env = Envelope.parseFrom(frame);
            MessageHeader header = env.getHeader();

            if (!EnvelopeCodec.verify(env, session.remotePublicKey())) {
                log.warn("Dropping message from {}: bad signature", remoteNodeId().shortId());
                return;
            }
            byte[] nonce = header.getNonce().toByteArray();
            if (!replayGuard.accept(nonce, header.getTimestamp())) {
                log.warn("Dropping replayed/stale message from {}", remoteNodeId().shortId());
                return;
            }
            byte[] aad = header.toByteArray();
            byte[] payload = session.cipher().decrypt(nonce, env.getBody().toByteArray(), aad);

            MessageType type = MessageType.fromCode(header.getMessageType());
            AegisMessage msg = new AegisMessage(remoteNodeId(), identity.nodeId(), type, payload);
            handler.onMessage(this, msg, header.getCorrelation(), header.getIsResponse());
        } catch (RejectedExecutionException e) {
            log.debug("Dropping inbound frame from {} during handler shutdown: {}",
                    remoteNodeId().shortId(), e.toString());
            closeQuietly();
        } catch (Exception e) {
            log.warn("Failed to handle inbound frame from {}: {}",
                    remoteNodeId().shortId(), e.toString());
        }
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        if (writerThread != null && writerThread != Thread.currentThread()) {
            writerThread.interrupt();
        }
        if (receiveThread != null && receiveThread != Thread.currentThread()) {
            receiveThread.interrupt();
        }
        try {
            handler.onConnectionClosed(this);
        } catch (Exception ignored) {
        }
    }
}

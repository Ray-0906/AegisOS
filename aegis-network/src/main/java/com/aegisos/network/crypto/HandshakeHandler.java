package com.aegisos.network.crypto;

import com.aegisos.core.crypto.X25519;
import com.aegisos.core.identity.IdentityService;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.message.MessageType;
import com.aegisos.network.wire.EnvelopeCodec;
import com.aegisos.network.wire.Framing;
import com.aegisos.proto.Envelope;
import com.aegisos.proto.Hello;
import com.aegisos.proto.MessageHeader;
import com.aegisos.proto.Verify;
import com.google.protobuf.ByteString;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Mutual-authentication handshake (design section 3.2):
 *
 * <pre>
 *   A -> B : Hello { nodeId, ed25519 pub, x25519 ephemeral pub, nonceA }
 *   B -> A : Hello { nodeId, ed25519 pub, x25519 ephemeral pub, nonceB }
 *   A -> B : Verify { sig_A(nonceB || nodeId_A) }
 *   B -> A : Verify { sig_B(nonceA || nodeId_B) }
 * </pre>
 *
 * Both sides derive the same AES-256-GCM session key from the ECDH shared secret and
 * the (order-independent) combination of both nonces.
 */
public final class HandshakeHandler {

    private static final Logger log = LoggerFactory.getLogger(HandshakeHandler.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final byte[] HKDF_INFO = "aegis-session-v1".getBytes();
    private static final int CHALLENGE_BYTES = 32;

    private final IdentityService identity;
    private final Supplier<String> advertisedAddress;

    public HandshakeHandler(IdentityService identity, Supplier<String> advertisedAddress) {
        this.identity = identity;
        this.advertisedAddress = advertisedAddress;
    }

    /** Client side of the handshake (we dialed the peer). */
    public EstablishedSession initiate(DataInputStream in, DataOutputStream out) throws IOException {
        log.trace("HANDSHAKE START (initiator)");
        X25519.EphemeralKeyPair eph = X25519.generate();
        byte[] nonceA = randomChallenge();

        log.trace("HELLO SENT (initiator)");
        sendHello(out, eph.publicKey(), nonceA);

        log.trace("HELLO RECEIVED (initiator)");
        Envelope peerHelloEnv = readEnvelope(in);
        Hello peerHello = parseHello(peerHelloEnv);
        NodeId peerId = trustPeer(peerHello, peerHelloEnv);

        byte[] sessionKey = deriveKey(eph.privateKey(), peerHello.getEphemeralPublicKey().toByteArray(),
                nonceA, peerHello.getNonce().toByteArray());

        log.trace("VERIFY SENT (initiator)");
        sendVerify(out, peerHello.getNonce().toByteArray());

        log.trace("VERIFY RECEIVED (initiator)");
        Envelope peerVerifyEnv = readEnvelope(in);
        verifyPeerVerify(peerVerifyEnv, peerHello, nonceA);

        log.trace("Handshake complete (initiator) with peer {}", peerId.shortId());
        return new EstablishedSession(peerId, peerHello.getEd25519PublicKey().toByteArray(),
                peerHello.getAddress(), new SessionCipher(sessionKey));
    }

    /** Server side of the handshake (the peer dialed us). */
    public EstablishedSession respond(DataInputStream in, DataOutputStream out) throws IOException {
        log.trace("HANDSHAKE START (responder)");
        log.trace("HELLO RECEIVED (responder)");
        Envelope peerHelloEnv = readEnvelope(in);
        Hello peerHello = parseHello(peerHelloEnv);
        NodeId peerId = trustPeer(peerHello, peerHelloEnv);

        X25519.EphemeralKeyPair eph = X25519.generate();
        byte[] nonceB = randomChallenge();
        log.trace("HELLO SENT (responder)");
        sendHello(out, eph.publicKey(), nonceB);

        byte[] sessionKey = deriveKey(eph.privateKey(), peerHello.getEphemeralPublicKey().toByteArray(),
                nonceB, peerHello.getNonce().toByteArray());

        log.trace("VERIFY RECEIVED (responder)");
        Envelope peerVerifyEnv = readEnvelope(in);
        verifyPeerVerify(peerVerifyEnv, peerHello, nonceB);

        log.trace("VERIFY SENT (responder)");
        sendVerify(out, peerHello.getNonce().toByteArray());

        log.trace("Handshake complete (responder) with peer {}", peerId.shortId());
        return new EstablishedSession(peerId, peerHello.getEd25519PublicKey().toByteArray(),
                peerHello.getAddress(), new SessionCipher(sessionKey));
    }

    // --- helpers ---------------------------------------------------------

    private void sendHello(DataOutputStream out, byte[] ephemeralPub, byte[] nonce) throws IOException {
        Hello hello = Hello.newBuilder()
                .setNodeId(ByteString.copyFrom(identity.nodeId().toBytes()))
                .setEd25519PublicKey(ByteString.copyFrom(identity.publicKey()))
                .setEphemeralPublicKey(ByteString.copyFrom(ephemeralPub))
                .setNonce(ByteString.copyFrom(nonce))
                .setAddress(advertisedAddress.get())
                .build();
        writeSigned(out, MessageType.HELLO, hello.toByteArray());
    }

    private void sendVerify(DataOutputStream out, byte[] peerNonce) throws IOException {
        byte[] toSign = concat(peerNonce, identity.nodeId().toBytes());
        Verify verify = Verify.newBuilder()
                .setNodeId(ByteString.copyFrom(identity.nodeId().toBytes()))
                .setSignature(ByteString.copyFrom(identity.sign(toSign)))
                .build();
        writeSigned(out, MessageType.VERIFY, verify.toByteArray());
    }

    private void writeSigned(DataOutputStream out, MessageType type, byte[] body) throws IOException {
        MessageHeader header = MessageHeader.newBuilder()
                .setSenderId(ByteString.copyFrom(identity.nodeId().toBytes()))
                .setMessageType(type.code())
                .setTimestamp(Instant.now().toEpochMilli())
                .setHandshake(true)
                .build();
        Envelope env = EnvelopeCodec.build(header, body, identity::sign);
        Framing.writeFrame(out, env.toByteArray());
    }

    private Envelope readEnvelope(DataInputStream in) throws IOException {
        byte[] frame = Framing.readFrame(in);
        if (frame == null) {
            throw new IOException("connection closed during handshake");
        }
        return Envelope.parseFrom(frame);
    }

    private Hello parseHello(Envelope env) throws IOException {
        if (env.getHeader().getMessageType() != MessageType.HELLO.code()) {
            throw new IOException("expected HELLO, got type " + env.getHeader().getMessageType());
        }
        Hello hello = Hello.parseFrom(env.getBody());
        if (!EnvelopeCodec.verify(env, hello.getEd25519PublicKey().toByteArray())) {
            throw new IOException("HELLO signature verification failed");
        }
        return hello;
    }

    private NodeId trustPeer(Hello hello, Envelope env) throws IOException {
        NodeId peerId = NodeId.of(hello.getNodeId().toByteArray());
        boolean trusted = identity.trustStore()
                .offerOnFirstUse(peerId, hello.getEd25519PublicKey().toByteArray());
        if (!trusted) {
            throw new IOException("peer " + peerId.shortId() + " rejected by trust policy");
        }
        return peerId;
    }

    private void verifyPeerVerify(Envelope env, Hello peerHello, byte[] myNonce) throws IOException {
        if (env.getHeader().getMessageType() != MessageType.VERIFY.code()) {
            throw new IOException("expected VERIFY, got type " + env.getHeader().getMessageType());
        }
        Verify verify;
        try {
            verify = Verify.parseFrom(env.getBody());
        } catch (Exception e) {
            throw new IOException("malformed VERIFY", e);
        }
        byte[] expectedSigned = concat(myNonce, peerHello.getNodeId().toByteArray());
        boolean ok = com.aegisos.core.crypto.Ed25519.verify(
                peerHello.getEd25519PublicKey().toByteArray(), expectedSigned,
                verify.getSignature().toByteArray());
        if (!ok) {
            throw new IOException("peer VERIFY challenge signature invalid");
        }
    }

    private byte[] deriveKey(X25519PrivateKeyParameters ourEph, byte[] peerEph,
                             byte[] ourNonce, byte[] peerNonce) {
        byte[] shared = X25519.sharedSecret(ourEph, peerEph);
        byte[] salt = orderIndependentSalt(ourNonce, peerNonce);
        return X25519.deriveSessionKey(shared, salt, HKDF_INFO);
    }

    private static byte[] orderIndependentSalt(byte[] a, byte[] b) {
        // Smaller nonce first so both peers derive an identical salt.
        boolean aFirst = compare(a, b) <= 0;
        return aFirst ? concat(a, b) : concat(b, a);
    }

    private static int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return a.length - b.length;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(a.length + b.length);
        bos.writeBytes(a);
        bos.writeBytes(b);
        return bos.toByteArray();
    }

    private static byte[] randomChallenge() {
        byte[] c = new byte[CHALLENGE_BYTES];
        RNG.nextBytes(c);
        return c;
    }
}

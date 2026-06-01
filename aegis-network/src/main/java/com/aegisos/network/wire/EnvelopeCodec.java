package com.aegisos.network.wire;

import com.aegisos.core.crypto.Ed25519;
import com.aegisos.proto.Envelope;
import com.aegisos.proto.MessageHeader;
import com.google.protobuf.ByteString;

import java.io.ByteArrayOutputStream;
import java.util.function.Function;

/**
 * Builds and verifies signed {@code Envelope}s. The Ed25519 signature covers
 * {@code header_bytes || body}, providing sender authentication independent of the
 * AES-GCM session (which provides confidentiality + integrity).
 */
public final class EnvelopeCodec {

    private EnvelopeCodec() {
    }

    public static byte[] signingBytes(MessageHeader header, byte[] body) {
        byte[] headerBytes = header.toByteArray();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(headerBytes.length + body.length);
        bos.writeBytes(headerBytes);
        bos.writeBytes(body);
        return bos.toByteArray();
    }

    /**
     * Builds a signed envelope. The {@code signer} maps the signing bytes to an Ed25519
     * signature; this is normally {@code IdentityService::sign}, keeping the private seed
     * encapsulated inside the identity service.
     */
    public static Envelope build(MessageHeader header, byte[] body, Function<byte[], byte[]> signer) {
        byte[] sig = signer.apply(signingBytes(header, body));
        return Envelope.newBuilder()
                .setHeader(header)
                .setBody(ByteString.copyFrom(body))
                .setSignature(ByteString.copyFrom(sig))
                .build();
    }

    public static boolean verify(Envelope envelope, byte[] senderPublicKey) {
        byte[] body = envelope.getBody().toByteArray();
        byte[] sig = envelope.getSignature().toByteArray();
        return Ed25519.verify(senderPublicKey, signingBytes(envelope.getHeader(), body), sig);
    }
}

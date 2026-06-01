package com.aegisos.core.identity;

import com.aegisos.core.crypto.Ed25519;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class IdentityServiceTest {

    @Test
    void nodeIdIsDerivedFromPublicKey() {
        IdentityService a = IdentityService.ephemeral();
        assertEquals(NodeId.LENGTH, a.nodeId().toBytes().length);
        // NodeID == SHA-256(publicKey)
        NodeId derived = NodeId.of(com.aegisos.core.crypto.Hashing.sha256(a.publicKey()));
        assertEquals(a.nodeId(), derived);
    }

    @Test
    void signThenVerifyRoundTrips() {
        IdentityService a = IdentityService.ephemeral();
        byte[] msg = "hello aegis".getBytes(StandardCharsets.UTF_8);
        byte[] sig = a.sign(msg);
        assertEquals(Ed25519.SIGNATURE_BYTES, sig.length);
        // verify against own pinned key
        assertTrue(a.verify(msg, sig, a.nodeId()));
    }

    @Test
    void tamperedMessageFailsVerification() {
        IdentityService a = IdentityService.ephemeral();
        byte[] msg = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] sig = a.sign(msg);
        byte[] tampered = "payl0ad".getBytes(StandardCharsets.UTF_8);
        assertFalse(a.verify(tampered, sig, a.nodeId()));
    }

    @Test
    void trustStoreRejectsMismatchedKey() {
        IdentityService a = IdentityService.ephemeral();
        IdentityService b = IdentityService.ephemeral();
        TrustStore store = new TrustStore();
        // Offering b's key under a's id must fail (id != sha256(key)).
        assertFalse(store.offerOnFirstUse(a.nodeId(), b.publicKey()));
        // Correct pairing accepted.
        assertTrue(store.offerOnFirstUse(b.nodeId(), b.publicKey()));
        assertTrue(store.isKnown(b.nodeId()));
    }

    @Test
    void unknownSenderCannotBeVerified() {
        IdentityService a = IdentityService.ephemeral();
        IdentityService b = IdentityService.ephemeral();
        byte[] msg = "x".getBytes(StandardCharsets.UTF_8);
        byte[] sig = b.sign(msg);
        // a does not know b -> verify fails for lack of pinned key
        assertFalse(a.verify(msg, sig, b.nodeId()));
    }
}

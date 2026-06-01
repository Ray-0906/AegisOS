package com.aegisos.network.wire;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplayGuardTest {

    @Test
    void acceptsFreshRejectsReplay() {
        ReplayGuard guard = new ReplayGuard(30_000);
        byte[] nonce = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        long now = System.currentTimeMillis();
        assertTrue(guard.accept(nonce, now), "first use must be accepted");
        assertFalse(guard.accept(nonce, now), "replayed nonce must be rejected");
    }

    @Test
    void rejectsStaleTimestamp() {
        ReplayGuard guard = new ReplayGuard(30_000);
        byte[] nonce = new byte[]{9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9};
        long old = System.currentTimeMillis() - 60_000;
        assertFalse(guard.accept(nonce, old), "timestamp outside the window must be rejected");
    }
}

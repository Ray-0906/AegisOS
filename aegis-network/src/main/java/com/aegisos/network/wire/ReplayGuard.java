package com.aegisos.network.wire;

import com.aegisos.core.util.HexUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Defends against replay attacks (design section 5):
 * <ul>
 *   <li>rejects messages whose timestamp is outside a +/- window (default 30s);</li>
 *   <li>rejects a nonce already seen within the window.</li>
 * </ul>
 */
public final class ReplayGuard {

    private final long windowMillis;
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public ReplayGuard(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    public ReplayGuard() {
        this(30_000L);
    }

    /** @return true if the message is fresh and should be accepted. */
    public boolean accept(byte[] nonce, long timestamp) {
        long now = System.currentTimeMillis();
        if (Math.abs(now - timestamp) > windowMillis) {
            return false;
        }
        evictExpired(now);
        String key = HexUtil.encode(nonce);
        Long prev = seenNonces.putIfAbsent(key, timestamp);
        return prev == null;
    }

    private void evictExpired(long now) {
        if (seenNonces.size() < 4096) {
            return;
        }
        seenNonces.entrySet().removeIf(e -> Math.abs(now - e.getValue()) > windowMillis);
    }
}

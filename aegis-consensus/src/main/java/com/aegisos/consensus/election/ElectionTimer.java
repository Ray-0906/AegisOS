package com.aegisos.consensus.election;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Randomized election timeout (default 150-300ms, design section 3.4). Resetting it
 * cancels and reschedules the timeout, so a follower that keeps hearing from the leader
 * never starts an election.
 */
public final class ElectionTimer {

    private final ScheduledExecutorService scheduler;
    private final Runnable onTimeout;
    private final long minMs;
    private final long maxMs;

    private volatile ScheduledFuture<?> pending;
    private volatile boolean enabled;

    public ElectionTimer(ScheduledExecutorService scheduler, long minMs, long maxMs, Runnable onTimeout) {
        this.scheduler = scheduler;
        this.minMs = minMs;
        this.maxMs = maxMs;
        this.onTimeout = onTimeout;
    }

    public synchronized void reset() {
        enabled = true;
        if (pending != null) {
            pending.cancel(false);
        }
        long delay = ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
        pending = scheduler.schedule(this::fire, delay, TimeUnit.MILLISECONDS);
    }

    private void fire() {
        if (enabled) {
            onTimeout.run();
        }
    }

    public synchronized void stop() {
        enabled = false;
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }
}

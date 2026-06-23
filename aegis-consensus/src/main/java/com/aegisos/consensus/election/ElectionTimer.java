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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ElectionTimer.class);

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
        if (scheduler.isShutdown() || scheduler.isTerminated()) {
            return;
        }
        if (pending != null) {
            pending.cancel(false);
        }
        long delay = ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
        try {
            if (log.isTraceEnabled()) {
                log.trace("RAFT_TASK_ENQUEUED=ELECTION_TIMEOUT");
            }
            long expectedRunTime = System.currentTimeMillis() + delay;
            pending = scheduler.schedule(() -> {
                long now = System.currentTimeMillis();
                if (log.isTraceEnabled()) {
                    log.trace("RAFT_TASK_STARTED=ELECTION_TIMEOUT");
                }
                com.aegisos.consensus.RaftLagMonitor.record("ELECTION_TIMEOUT", now - expectedRunTime);
                this.fire();
            }, delay, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Executor was shut down right after our check; benign race condition during shutdown.
        }
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

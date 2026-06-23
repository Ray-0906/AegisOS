package com.aegisos.consensus;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

public class RaftLagMonitor {
    public static class Stats {
        public final AtomicLong maxLag = new AtomicLong(0);
        public final AtomicLong totalLag = new AtomicLong(0);
        public final AtomicInteger count = new AtomicInteger(0);
    }
    public static final ConcurrentHashMap<String, Stats> STATS = new ConcurrentHashMap<>();
    
    public static final AtomicInteger RAFT_CREATED_TOTAL = new AtomicInteger(0);
    public static final AtomicInteger RAFT_CLOSED_TOTAL = new AtomicInteger(0);

    // H6 investigation counters
    public static final AtomicInteger DISRUPTIVE_REQUEST_VOTE_TOTAL = new AtomicInteger(0);
    public static final AtomicInteger LEADER_STEPDOWN_TOTAL = new AtomicInteger(0);

    /** Current test name — set by test harness, read by instrumentation. */
    public static volatile String currentTestName = "unknown";
    
    public static void record(String name, long lag) {
        Stats s = STATS.computeIfAbsent(name, k -> new Stats());
        s.count.incrementAndGet();
        s.totalLag.addAndGet(lag);
        long currentMax;
        do {
            currentMax = s.maxLag.get();
        } while (lag > currentMax && !s.maxLag.compareAndSet(currentMax, lag));
    }
}

package com.aegisos.cluster.jobs;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;

/** Sleeps but ignores interrupts. */
public final class UninterruptibleSleepJob implements AegisJob<Boolean> {
    private final long sleepMs;

    public UninterruptibleSleepJob(long sleepMs) {
        this.sleepMs = sleepMs;
    }

    public UninterruptibleSleepJob(String[] args) {
        this(args.length > 0 ? Long.parseLong(args[0]) : 30000L);
    }

    public UninterruptibleSleepJob() {
        this(30000);
    }

    @Override
    public Boolean execute(JobContext ctx) {
        long end = System.currentTimeMillis() + sleepMs;
        while (System.currentTimeMillis() < end) {
            try {
                Thread.sleep(end - System.currentTimeMillis());
            } catch (InterruptedException e) {
                // Ignore
            }
        }
        return true;
    }
}

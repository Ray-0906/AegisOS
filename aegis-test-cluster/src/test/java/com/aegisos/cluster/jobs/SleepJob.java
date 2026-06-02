package com.aegisos.cluster.jobs;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;

/** Sleeps for a configurable amount of time. */
public final class SleepJob implements AegisJob<Boolean> {

    private final long sleepMs;

    public SleepJob(long sleepMs) {
        this.sleepMs = sleepMs;
    }

    public SleepJob() {
        this(30000);
    }

    @Override
    public Boolean execute(JobContext ctx) {
        try {
            System.out.println("SleepJob started: " + ctx.jobId());
            Thread.sleep(sleepMs);
            System.out.println("SleepJob finished: " + ctx.jobId());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted");
        }
    }
}

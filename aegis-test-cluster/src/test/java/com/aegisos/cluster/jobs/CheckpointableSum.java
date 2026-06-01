package com.aegisos.cluster.jobs;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;

import java.io.Serializable;

/**
 * A deliberately long-running, checkpointable job: it sums 1..target one step at a time
 * with a small delay, periodically exposing its progress via {@link #captureState()} so it
 * can resume after migration.
 */
public final class CheckpointableSum implements AegisJob<Long> {

    private final int target;
    private final long stepMillis;
    private int current = 1;
    private long sum = 0;

    public CheckpointableSum(int target, long stepMillis) {
        this.target = target;
        this.stepMillis = stepMillis;
    }

    @Override
    public Long execute(JobContext ctx) throws Exception {
        while (current <= target) {
            sum += current;
            current++;
            Thread.sleep(stepMillis);
        }
        return sum;
    }

    @Override
    public Serializable captureState() {
        return new long[]{current, sum};
    }

    @Override
    public void restoreState(Serializable state) {
        long[] s = (long[]) state;
        this.current = (int) s[0];
        this.sum = s[1];
    }
}

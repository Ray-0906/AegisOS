package com.aegisos.cluster.jobs;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;

/** Counts the prime numbers in the range [2, limit]. Simple deterministic test job. */
public final class PrimeCounter implements AegisJob<Long> {

    private final int limit;

    public PrimeCounter(int limit) {
        this.limit = limit;
    }

    @Override
    public Long execute(JobContext ctx) {
        long count = 0;
        for (int n = 2; n <= limit; n++) {
            if (isPrime(n)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isPrime(int n) {
        if (n < 2) {
            return false;
        }
        for (int i = 2; (long) i * i <= n; i++) {
            if (n % i == 0) {
                return false;
            }
        }
        return true;
    }
}

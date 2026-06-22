package com.aegisos.examples;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;

public class PrimeCounter implements AegisJob<Integer> {
    private final int max;

    public PrimeCounter(String[] args) {
        if (args != null && args.length > 0) {
            this.max = Integer.parseInt(args[0]);
        } else {
            this.max = 100000;
        }
    }

    public PrimeCounter() {
        this.max = 100000;
    }

    @Override
    public Integer execute(JobContext ctx) throws Exception {
        System.out.println("Counting primes up to " + max);
        int count = 0;
        for (int i = 2; i <= max; i++) {
            if (isPrime(i)) {
                count++;
            }
        }
        System.out.println("Found " + count + " primes.");
        return count;
    }

    private boolean isPrime(int n) {
        if (n <= 1) return false;
        if (n <= 3) return true;
        if (n % 2 == 0 || n % 3 == 0) return false;
        for (int i = 5; i * i <= n; i = i + 6) {
            if (n % i == 0 || n % (i + 2) == 0) return false;
        }
        return true;
    }
}

package com.aegisos.core;

import java.util.concurrent.ThreadLocalRandom;

public class SchedulerJitter {
    public static long jitter(long initialDelay, long period) {
        if (period > 0) {
            return ThreadLocalRandom.current().nextLong(period);
        }
        return initialDelay;
    }
}

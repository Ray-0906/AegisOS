package com.aegisos.testing;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

public class EventAwaiter {
    private Duration timeout = Duration.ofSeconds(30);
    private Duration pollInterval = Duration.ofMillis(50);

    public EventAwaiter() {}

    public EventAwaiter withTimeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public EventAwaiter withPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
        return this;
    }

    public void await(BooleanSupplier condition) throws TimeoutException, InterruptedException {
        long start = System.currentTimeMillis();
        long deadline = start + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(pollInterval.toMillis());
        }
        throw new TimeoutException("Condition not met within " + timeout);
    }
}

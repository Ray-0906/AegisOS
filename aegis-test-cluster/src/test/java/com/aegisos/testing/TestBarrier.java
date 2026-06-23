package com.aegisos.testing;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TestBarrier {
    private final CountDownLatch latch;

    public TestBarrier(int parties) {
        this.latch = new CountDownLatch(parties);
    }

    public void arrive() {
        latch.countDown();
    }

    public void await(Duration timeout) throws TimeoutException, InterruptedException {
        if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Barrier not reached within " + timeout);
        }
    }
}

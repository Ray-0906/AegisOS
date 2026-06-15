package com.aegisos.core.observability;

import java.util.concurrent.atomic.AtomicLong;

public final class Gauge {
    private final String name;
    private final AtomicLong value;

    public Gauge(String name) {
        this.name = name;
        this.value = new AtomicLong(0);
    }

    public void set(long val) {
        value.set(val);
    }

    public void increment() {
        value.incrementAndGet();
    }

    public void decrement() {
        value.decrementAndGet();
    }

    public long get() {
        return value.get();
    }

    public String getName() {
        return name;
    }
}

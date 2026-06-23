package com.aegisos.core.observability;

import java.util.concurrent.atomic.LongAdder;

public final class Counter {
    private final String name;
    private final LongAdder value;

    public Counter(String name) {
        this.name = name;
        this.value = new LongAdder();
    }

    public void increment() {
        value.increment();
    }

    public void add(long delta) {
        value.add(delta);
    }

    public long get() {
        return value.sum();
    }

    public String getName() {
        return name;
    }
}

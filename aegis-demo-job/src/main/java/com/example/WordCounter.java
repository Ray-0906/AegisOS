package com.example;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;

import java.util.Arrays;

public final class WordCounter implements AegisJob<Long> {
    private final String text;

    public WordCounter(String[] args) {
        this.text = String.join(" ", args);
    }

    @Override
    public Long execute(JobContext ctx) {
        long count = Arrays.stream(text.split("\\s+"))
                .filter(s -> !s.isEmpty()).count();
        System.out.println("WordCounter: counted " + count + " words");
        return count;
    }
}

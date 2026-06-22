package com.aegisos.examples;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
import java.util.HashMap;
import java.util.HashMap;

public class WordCounter implements AegisJob<HashMap<String, Integer>> {
    private final String text;

    public WordCounter(String[] args) {
        if (args != null && args.length > 0) {
            this.text = String.join(" ", args);
        } else {
            this.text = "aegis is a distributed operating system aegis runs jobs aegis stores files";
        }
    }

    public WordCounter() {
        this(null);
    }

    @Override
    public HashMap<String, Integer> execute(JobContext ctx) throws Exception {
        System.out.println("Counting words in: '" + text + "'");
        HashMap<String, Integer> counts = new HashMap<>();
        String[] words = text.toLowerCase().split("\\W+");
        
        for (String word : words) {
            if (word.isEmpty()) continue;
            counts.put(word, counts.getOrDefault(word, 0) + 1);
        }
        
        counts.forEach((k, v) -> System.out.println(k + ": " + v));
        return counts;
    }
}

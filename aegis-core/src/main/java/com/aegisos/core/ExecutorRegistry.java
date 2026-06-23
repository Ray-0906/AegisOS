package com.aegisos.core;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public class ExecutorRegistry {
    public static final ConcurrentHashMap<String, ExecutorService> EXECUTORS = new ConcurrentHashMap<>();
    
    public static <T extends ExecutorService> T register(String name, T executor) {
        String key = name + "-" + UUID.randomUUID().toString().substring(0, 4);
        EXECUTORS.put(key, executor);
        return executor;
    }
}

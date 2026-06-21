package com.aegisos.cluster;

import com.aegisos.node.AegisNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

public class StatsTracker {
    public static final AtomicInteger NODE_COUNT = new AtomicInteger();
    
    public static void dump(String phase, java.util.List<AegisNode> nodes) {
        if ("start".equals(phase) || "TEST_BEGIN".equals(phase)) {
            System.out.println("TEST_BEGIN");
        } else {
            System.out.println("TEST_END");
        }
    }
}

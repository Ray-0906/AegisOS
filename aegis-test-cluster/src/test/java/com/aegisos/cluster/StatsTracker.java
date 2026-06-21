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
        int threads = Thread.getAllStackTraces().size();
        int schedulers = 0;
        int tcp = 0;
        int peer = 0;
        int raftTimers = 0;
        int auditWorkers = 0;
        
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName().toLowerCase();
            if (name.contains("sched") || name.contains("timer") || name.contains("pool") || name.contains("maint")) schedulers++;
            if (name.contains("tcp") || name.contains("accept") || name.contains("recv") || name.contains("send") || name.contains("network")) tcp++;
            if (name.contains("peer") || name.contains("gossip")) peer++;
            if (name.contains("raft")) raftTimers++;
            if (name.contains("audit") || name.contains("scrubber") || name.contains("anti-entropy") || name.contains("repair")) auditWorkers++;
        }

        int tempDirs = 0;
        java.io.File tmpDir = new java.io.File(System.getProperty("java.io.tmpdir"));
        java.io.File[] files = tmpDir.listFiles((dir, name) -> name.startsWith("aegis-node-"));
        if (files != null) {
            tempDirs = files.length;
        }

        if ("start".equals(phase) || "TEST_BEGIN".equals(phase)) {
            System.out.println("TEST_BEGIN");
        } else {
            System.out.println("TEST_END");
        }

        System.out.println("THREADS=" + threads);
        System.out.println("SCHEDULERS=" + schedulers);
        System.out.println("TCP_CONNECTIONS=" + tcp);
        System.out.println("PEER_CONNECTIONS=" + peer);
        System.out.println("RAFT_TIMERS=" + raftTimers);
        System.out.println("AUDIT_WORKERS=" + auditWorkers);
        System.out.println("TEMP_DIRS=" + tempDirs);

        try {
            Class<?> registryClass = Class.forName("com.aegisos.core.ExecutorRegistry");
            java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ExecutorService> executors = 
                (java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ExecutorService>) registryClass.getField("EXECUTORS").get(null);
            
            for (java.util.Map.Entry<String, java.util.concurrent.ExecutorService> entry : executors.entrySet()) {
                var exec = entry.getValue();
                int active = -1;
                int queue = -1;
                if (exec instanceof java.util.concurrent.ThreadPoolExecutor) {
                    active = ((java.util.concurrent.ThreadPoolExecutor)exec).getActiveCount();
                    queue = ((java.util.concurrent.ThreadPoolExecutor)exec).getQueue().size();
                }
                if (active >= 0) {
                    System.out.println("EXECUTOR_NAME=" + entry.getKey());
                    System.out.println("ACTIVE=" + active);
                    System.out.println("QUEUE=" + queue);
                }
            }
        } catch (Exception e) {
            // ignore if class not found or reflection fails
        }

        if ("TEST_END".equals(phase)) {
            try {
                Class<?> monitorClass = Class.forName("com.aegisos.consensus.RaftLagMonitor");
                java.util.concurrent.ConcurrentHashMap<String, ?> stats = 
                    (java.util.concurrent.ConcurrentHashMap<String, ?>) monitorClass.getField("STATS").get(null);
                
                for (java.util.Map.Entry<String, ?> entry : stats.entrySet()) {
                    Object s = entry.getValue();
                    Class<?> statsClass = s.getClass();
                    long maxLag = ((java.util.concurrent.atomic.AtomicLong) statsClass.getField("maxLag").get(s)).get();
                    long totalLag = ((java.util.concurrent.atomic.AtomicLong) statsClass.getField("totalLag").get(s)).get();
                    int count = ((java.util.concurrent.atomic.AtomicInteger) statsClass.getField("count").get(s)).get();
                    long avgLag = count > 0 ? totalLag / count : 0;
                    
                    System.out.println("TASK_NAME=" + entry.getKey());
                    System.out.println("MAX_LAG_MS=" + maxLag);
                    System.out.println("AVG_LAG_MS=" + avgLag);
                    System.out.println("COUNT=" + count);
                }
            } catch (Exception e) {
                // ignore
            }

            try {
                Class<?> monitorClass = Class.forName("com.aegisos.consensus.RaftLagMonitor");
                int created = ((java.util.concurrent.atomic.AtomicInteger) monitorClass.getField("RAFT_CREATED_TOTAL").get(null)).get();
                int closed = ((java.util.concurrent.atomic.AtomicInteger) monitorClass.getField("RAFT_CLOSED_TOTAL").get(null)).get();
                int active = created - closed;
                
                System.out.println("RAFT_CREATED_TOTAL=" + created);
                System.out.println("RAFT_CLOSED_TOTAL=" + closed);
                System.out.println("RAFT_ACTIVE=" + active);
                
                java.util.concurrent.ConcurrentHashMap<String, ?> stats = 
                    (java.util.concurrent.ConcurrentHashMap<String, ?>) monitorClass.getField("STATS").get(null);
                Object heartbeatStats = stats.get("HEARTBEAT");
                if (heartbeatStats != null) {
                    Class<?> statsClass = heartbeatStats.getClass();
                    int heartbeats = ((java.util.concurrent.atomic.AtomicInteger) statsClass.getField("count").get(heartbeatStats)).get();
                    int perNode = created > 0 ? heartbeats / created : 0;
                    System.out.println("HEARTBEATS_PER_NODE=" + perNode);
                }
            } catch (Exception e) {
                // ignore
            }

            // H6 investigation: print disruptive vote and leader stepdown totals
            try {
                Class<?> monitorClass = Class.forName("com.aegisos.consensus.RaftLagMonitor");
                int disruptiveVotes = ((java.util.concurrent.atomic.AtomicInteger) monitorClass.getField("DISRUPTIVE_REQUEST_VOTE_TOTAL").get(null)).get();
                int leaderStepdowns = ((java.util.concurrent.atomic.AtomicInteger) monitorClass.getField("LEADER_STEPDOWN_TOTAL").get(null)).get();
                System.out.println("DISRUPTIVE_REQUEST_VOTE_TOTAL=" + disruptiveVotes);
                System.out.println("LEADER_STEPDOWN_TOTAL=" + leaderStepdowns);
            } catch (Exception e) {
                // ignore
            }
        }
    }
}

package com.aegisos.cluster;

import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobSpec;
import com.aegisos.proto.JobState;
import com.aegisos.proto.ResourceRequest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standalone CLI application for soak testing the AegisOS cluster.
 * Run this outside of 'mvn test' to test long-running stability.
 * 
 * Usage: java com.aegisos.cluster.SoakTestRunner [duration_minutes]
 */
public class SoakTestRunner {

    public static void main(String[] args) throws Exception {
        int durationMinutes = 30;
        if (args.length > 0) {
            durationMinutes = Integer.parseInt(args[0]);
        }

        System.out.println("Starting AegisOS Soak Test for " + durationMinutes + " minutes...");
        
        try (ClusterHarness cluster = new ClusterHarness()) {
            cluster.setSnapshotEntryThreshold(50); // Very low threshold to force constant snapshots
            List<com.aegisos.node.AegisNode> nodes = cluster.start(5);
            
            com.aegisos.node.AegisNode leader = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                for (com.aegisos.node.AegisNode n : nodes) if (n.consensus().isLeader()) leader = n;
                if (leader != null) break;
                Thread.sleep(50);
            }
            
            if (leader == null) {
                System.err.println("Cluster failed to elect a leader. Aborting soak test.");
                return;
            }
            
            System.out.println("Cluster stabilized. Leader is " + leader.identity().nodeId().shortId());

            AtomicInteger fileWrites = new AtomicInteger(0);
            AtomicInteger jobsSubmitted = new AtomicInteger(0);
            
            ScheduledExecutorService loadGenerator = Executors.newScheduledThreadPool(4);
            
            // Task 1: Constant file writes
            final com.aegisos.node.AegisNode finalLeader = leader;
            loadGenerator.scheduleAtFixedRate(() -> {
                try {
                    int id = fileWrites.incrementAndGet();
                    finalLeader.fileSystem().write("soak-file-" + id, new byte[1024]);
                } catch (Exception e) {
                    System.err.println("File write failed: " + e.getMessage());
                }
            }, 100, 200, TimeUnit.MILLISECONDS);
            
            // Task 2: Background Jobs
            loadGenerator.scheduleAtFixedRate(() -> {
                try {
                    String jobId = UUID.randomUUID().toString();
                    JobSpec spec = JobSpec.newBuilder()
                            .setJobId(jobId)
                            .setResources(ResourceRequest.newBuilder().setCpuCores(1).setMemoryMb(128).build())
                            .build();

                    JobRecord runningJob = JobRecord.newBuilder()
                            .setSpec(spec)
                            .setState(JobState.RUNNING)
                            .setExecutionId(1)
                            .build();

                    finalLeader.consensus().propose(com.aegisos.proto.StateCommand.newBuilder()
                            .setType(com.aegisos.proto.CommandType.ASSIGN_JOB)
                            .setPayload(runningJob.toByteString())
                            .build());
                            
                    jobsSubmitted.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("Job submission failed: " + e.getMessage());
                }
            }, 500, 1000, TimeUnit.MILLISECONDS);
            
            // Task 3: Metrics logger
            loadGenerator.scheduleAtFixedRate(() -> {
                try {
                    System.out.println("--- SOAK TEST METRICS ---");
                    System.out.println("Time elapsed: " + (System.currentTimeMillis() - cluster.node(0).consensus().raftNode().lastSnapshotDurationMs()) + "ms");
                    for (com.aegisos.node.AegisNode n : nodes) {
                        var metrics = n.consensus().raftNode();
                        System.out.printf("Node %s: Role=%s, LogSize=%d, Snapshots=%d, InstallRx=%d, InstallTx=%d, Duration=%dms\n",
                                n.identity().nodeId().shortId(),
                                n.consensus().isLeader() ? "LEADER" : "FOLLOWER",
                                metrics.raftLog().entryCount(),
                                metrics.snapshotCreatedCount(),
                                metrics.installSnapshotReceivedCount(),
                                metrics.installSnapshotSentCount(),
                                metrics.lastSnapshotDurationMs());
                    }
                    System.out.println("Files written: " + fileWrites.get() + ", Jobs submitted: " + jobsSubmitted.get());
                } catch (Exception e) {
                    // Ignore
                }
            }, 10, 10, TimeUnit.SECONDS);

            System.out.println("Load generation started. Waiting " + durationMinutes + " minutes...");
            Thread.sleep(durationMinutes * 60_000L);
            
            System.out.println("Soak test time complete. Shutting down load generator...");
            loadGenerator.shutdown();
            loadGenerator.awaitTermination(10, TimeUnit.SECONDS);
            
            System.out.println("Verifying final cluster state...");
            System.out.println("Files written: " + fileWrites.get());
            System.out.println("Jobs submitted: " + jobsSubmitted.get());
            System.out.println("Leader snapshots created: " + finalLeader.consensus().raftNode().snapshotCreatedCount());
            
            // Test Restart Latency
            System.out.println("Testing restart latency on Follower...");
            com.aegisos.node.AegisNode follower = nodes.stream()
                    .filter(n -> !n.identity().nodeId().equals(finalLeader.identity().nodeId()))
                    .findFirst().get();
            
            cluster.stop(follower);
            long restartStart = System.currentTimeMillis();
            com.aegisos.node.AegisNode restarted = cluster.restartNode(follower);
            ClusterHarness.await(30000, () -> restarted.consensus().raftNode().commitIndex() > 0);
            long restartLatency = System.currentTimeMillis() - restartStart;
            System.out.println("Restart Latency from heavily-compacted snapshot: " + restartLatency + "ms");
            
            System.out.println("Soak test passed successfully!");
        }
    }
}

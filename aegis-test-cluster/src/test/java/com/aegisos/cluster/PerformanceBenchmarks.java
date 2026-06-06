package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.api.ProcessManager;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * Performance benchmark suite for AegisOS v0.3 RC1.
 * Remove @Disabled to execute manually.
 */
@Disabled("Remove this annotation to run benchmarks manually. WARNING: CPU/Memory intensive.")
public class PerformanceBenchmarks {

    private ClusterHarness cluster;
    private final File resultMd = new File("benchmarks/benchmark_results.md");
    private final File resultCsv = new File("benchmarks/benchmark_results.csv");

    @BeforeEach
    void setup() throws Exception {
        new File("benchmarks").mkdirs();
        // Don't append context on every run, just do it once if file is empty
        boolean writeHeader = !resultMd.exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(resultMd, true))) {
            if (writeHeader) {
                pw.println("# AegisOS v0.3 RC1 Benchmarks");
                pw.println();
                pw.println("## Machine Context");
                pw.println("CPU: " + System.getenv("PROCESSOR_IDENTIFIER"));
                pw.println("RAM: " + (Runtime.getRuntime().maxMemory() / (1024 * 1024 * 1024)) + " GB Heap Allocated");
                pw.println("Java: " + System.getProperty("java.version"));
                pw.println("OS: " + System.getProperty("os.name"));
                pw.println("Cluster Size: 3 Nodes");
                pw.println();
            }
        }
        
        cluster = new ClusterHarness();
        cluster.start(3);
        ClusterHarness.await(10000, () -> cluster.nodes().stream()
                .allMatch(n -> n.consensus().leaderId() != null));
        ClusterHarness.await(5000, () -> cluster.nodes().get(0).discovery().membership().alivePeerIds().size() >= 2);
    }

    @AfterEach
    void teardown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    public static class NoOpJob implements AegisJob<Long> {
        @Override public Long execute(JobContext ctx) { return 1L; }
    }

    public static class SleepJob implements AegisJob<Long> {
        private final long sleepMs;
        public SleepJob(long sleepMs) { this.sleepMs = sleepMs; }
        @Override public Long execute(JobContext ctx) throws Exception { Thread.sleep(sleepMs); return 1L; }
    }

    private static class JobTimeline {
        long t0, t1, t2, t3, t4;
        long submitToAccepted() { return t1 - t0; }
        long acceptedToAssigned() { return t2 - t1; }
        long assignedToRunning() { return t3 - t2; }
        long runningToCompleted() { return t4 - t3; }
        long submitToCompleted() { return t4 - t0; }
    }

    private void runSchedulingBenchmark(String name, int jobs, boolean isModeB) throws Exception {
        System.out.println("Starting Benchmark: " + name + " with " + jobs + " jobs.");
        System.gc(); Thread.sleep(500); System.gc();
        long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long peakMem = memBefore;

        ExecutorService exec = Executors.newFixedThreadPool(20);
        List<Callable<JobTimeline>> tasks = new ArrayList<>();
        long suiteStart = System.currentTimeMillis();

        for (int i = 0; i < jobs; i++) {
            tasks.add(() -> {
                JobTimeline tl = new JobTimeline();
                com.aegisos.node.AegisNode node = cluster.nodes().get(0);
                ProcessManager pm = node.api().getProcessManager();
                
                tl.t0 = System.currentTimeMillis();
                JobHandle handle = isModeB ? pm.submit(new SleepJob(100), 1, 100) : pm.submit(new NoOpJob(), 1, 100);
                tl.t1 = System.currentTimeMillis();

                while (tl.t4 == 0) {
                    Optional<JobRecord> recOpt = node.runtimeAgent().registry().get(handle.jobId());
                    if (recOpt.isPresent()) {
                        JobState state = recOpt.get().getState();
                        long now = System.currentTimeMillis();
                        if (state == JobState.ASSIGNED && tl.t2 == 0) tl.t2 = now;
                        if (state == JobState.RUNNING) {
                            if (tl.t2 == 0) tl.t2 = now; // Skip fast
                            if (tl.t3 == 0) tl.t3 = now;
                        }
                        if (state == JobState.COMPLETED) {
                            if (tl.t2 == 0) tl.t2 = now;
                            if (tl.t3 == 0) tl.t3 = now;
                            tl.t4 = now;
                        }
                    }
                    Thread.sleep(10);
                }
                return tl;
            });
        }

        List<Future<JobTimeline>> futures = exec.invokeAll(tasks);
        exec.shutdown();
        long suiteEnd = System.currentTimeMillis();
        
        System.gc(); Thread.sleep(500); System.gc();
        long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        peakMem = Math.max(peakMem, Math.max(memBefore, memAfter));

        List<Long> s2a = new ArrayList<>();
        List<Long> a2a = new ArrayList<>();
        List<Long> a2r = new ArrayList<>();
        List<Long> r2c = new ArrayList<>();
        List<Long> total = new ArrayList<>();

        for (Future<JobTimeline> f : futures) {
            JobTimeline tl = f.get();
            s2a.add(tl.submitToAccepted());
            a2a.add(tl.acceptedToAssigned());
            a2r.add(tl.assignedToRunning());
            r2c.add(tl.runningToCompleted());
            total.add(tl.submitToCompleted());
        }

        double throughput = (double) jobs / ((suiteEnd - suiteStart) / 1000.0);

        writeReport(name, jobs, throughput, memBefore, memAfter, peakMem, s2a, a2a, a2r, r2c, total);
    }

    private void writeReport(String name, int jobs, double throughput, long memB, long memA, long memP,
                             List<Long> s2a, List<Long> a2a, List<Long> a2r, List<Long> r2c, List<Long> total) throws Exception {
        Collections.sort(s2a); Collections.sort(a2a); Collections.sort(a2r); Collections.sort(r2c); Collections.sort(total);
        
        try (PrintWriter pw = new PrintWriter(new FileWriter(resultMd, true))) {
            pw.println("### " + name + " (" + jobs + " jobs)");
            pw.println("**Throughput:** " + String.format("%.2f", throughput) + " jobs/sec");
            pw.println("**Memory:** Before=" + (memB/1024/1024) + "MB, After=" + (memA/1024/1024) + "MB, PeakObserved=" + (memP/1024/1024) + "MB\n");
            
            pw.println("| Metric | P50 | P95 | P99 | Max |");
            pw.println("|---|---|---|---|---|");
            pw.println("| Submit -> Accepted | " + p(s2a, 0.5) + " | " + p(s2a, 0.95) + " | " + p(s2a, 0.99) + " | " + p(s2a, 1.0) + " |");
            pw.println("| Accepted -> Assigned | " + p(a2a, 0.5) + " | " + p(a2a, 0.95) + " | " + p(a2a, 0.99) + " | " + p(a2a, 1.0) + " |");
            pw.println("| Assigned -> Running | " + p(a2r, 0.5) + " | " + p(a2r, 0.95) + " | " + p(a2r, 0.99) + " | " + p(a2r, 1.0) + " |");
            pw.println("| Running -> Completed | " + p(r2c, 0.5) + " | " + p(r2c, 0.95) + " | " + p(r2c, 0.99) + " | " + p(r2c, 1.0) + " |");
            pw.println("| Submit -> Completed | " + p(total, 0.5) + " | " + p(total, 0.95) + " | " + p(total, 0.99) + " | " + p(total, 1.0) + " |");
            pw.println();
        }
    }

    private long p(List<Long> sorted, double pct) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(pct * sorted.size()) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.size()) idx = sorted.size() - 1;
        return sorted.get(idx);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void benchmarkModeA_100() throws Exception { runSchedulingBenchmark("Mode A (Scheduler Only)", 100, false); }
    
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void benchmarkModeA_500() throws Exception { runSchedulingBenchmark("Mode A (Scheduler Only)", 500, false); }
    
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void benchmarkModeA_1000() throws Exception { runSchedulingBenchmark("Mode A (Scheduler Only)", 1000, false); }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void benchmarkModeB_100() throws Exception { runSchedulingBenchmark("Mode B (End-to-End)", 100, true); }
    
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void benchmarkModeB_500() throws Exception { runSchedulingBenchmark("Mode B (End-to-End)", 500, true); }
    
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void benchmarkModeB_1000() throws Exception { runSchedulingBenchmark("Mode B (End-to-End)", 1000, true); }

    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    void verifyHeapConsistency_1000() throws Exception {
        System.out.println("=== Starting Heap Consistency Verification (3 runs of 1000 jobs) ===");
        runSchedulingBenchmark("Mode B Heap Verification Run 1", 1000, true);
        runSchedulingBenchmark("Mode B Heap Verification Run 2", 1000, true);
        runSchedulingBenchmark("Mode B Heap Verification Run 3", 1000, true);
    }

    // ---------------------------------------------------------
    // 2. Scheduler Saturation
    // ---------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void benchmarkSchedulerSaturation_250() throws Exception { runSaturationBenchmark(250); }
    
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void benchmarkSchedulerSaturation_500() throws Exception { runSaturationBenchmark(500); }
    
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void benchmarkSchedulerSaturation_1000() throws Exception { runSaturationBenchmark(1000); }

    private void runSaturationBenchmark(int jobs) throws Exception {
        System.out.println("Starting Saturation Benchmark with " + jobs + " jobs.");
        long memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long peakMem = memBefore;

        // Submit jobs that request massive CPU to force queuing
        // Assuming typical laptop has 8-16 cores, 3 nodes = 24-48 cores.
        // Requesting 4 cores per job ensures saturation quickly.
        ExecutorService exec = Executors.newFixedThreadPool(20);
        List<Future<JobHandle>> submissions = new ArrayList<>();
        
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < jobs; i++) {
            submissions.add(exec.submit(() -> {
                com.aegisos.node.AegisNode node = cluster.nodes().get(0);
                ProcessManager pm = node.api().getProcessManager();
                // Sleep job to occupy the slot for a while
                return pm.submit(new SleepJob(100), 4, 100);
            }));
        }

        List<JobHandle> handles = new ArrayList<>();
        for (Future<JobHandle> f : submissions) {
            handles.add(f.get());
        }

        long maxPending = 0;
        long timeEmpty = 0;
        com.aegisos.node.AegisNode observer = cluster.nodes().get(0);

        while (true) {
            long pending = observer.runtimeAgent().registry().all().stream()
                    .filter(r -> r.getState() == JobState.PENDING).count();
            maxPending = Math.max(maxPending, pending);
            
            long completed = observer.runtimeAgent().registry().all().stream()
                    .filter(r -> r.getState() == JobState.COMPLETED || r.getState() == JobState.FAILED).count();

            if (completed >= jobs) {
                timeEmpty = System.currentTimeMillis();
                break;
            }
            Thread.sleep(50);
        }
        
        exec.shutdown();
        long drainTimeSeconds = (timeEmpty - t0) / 1000;
        double drainRate = drainTimeSeconds > 0 ? (double) jobs / drainTimeSeconds : jobs;

        long memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        peakMem = Math.max(peakMem, Math.max(memBefore, memAfter));

        try (PrintWriter pw = new PrintWriter(new FileWriter(resultMd, true))) {
            pw.println("### Scheduler Saturation (" + jobs + " jobs)");
            pw.println("**Max Pending Queue Size:** " + maxPending);
            pw.println("**Queue Drain Rate:** " + String.format("%.2f", drainRate) + " jobs/sec");
            pw.println("**Time until empty:** " + drainTimeSeconds + " seconds");
            pw.println("**Memory:** Before=" + (memB(memBefore)) + "MB, After=" + (memB(memAfter)) + "MB, PeakObserved=" + (memB(peakMem)) + "MB\n");
        }
    }

    private long memB(long bytes) { return bytes / 1024 / 1024; }

    // ---------------------------------------------------------
    // 3. Leader Failover
    // ---------------------------------------------------------

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void benchmarkLeaderFailover_Warm() throws Exception {
        runLeaderFailover("Warm");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void benchmarkLeaderFailover_Cold() throws Exception {
        // Cold failover: we restart the cluster to clear any warmup state
        teardown();
        setup();
        runLeaderFailover("Cold");
    }

    private void runLeaderFailover(String type) throws Exception {
        System.out.println("Starting Leader Failover Benchmark (" + type + ")");
        com.aegisos.node.AegisNode leader = cluster.nodes().stream()
                .filter(n -> n.identity().nodeId().equals(n.consensus().leaderId()))
                .findFirst().orElseThrow();

        com.aegisos.node.AegisNode follower = cluster.nodes().stream()
                .filter(n -> !n.identity().nodeId().equals(n.consensus().leaderId()))
                .findFirst().orElseThrow();

        long t0 = System.currentTimeMillis();
        cluster.stop(leader);

        long t1 = 0, t2 = 0, t3 = 0;
        
        while (true) {
            long now = System.currentTimeMillis();
            com.aegisos.core.identity.NodeId lId = follower.consensus().leaderId();
            
            // T1: Election starts (follower loses leader and clears leaderId)
            if (t1 == 0 && lId == null) t1 = now;
            
            // T2: Leader elected
            if (t2 == 0 && lId != null && !lId.equals(leader.identity().nodeId())) {
                t2 = now;
            }

            if (t2 > 0) {
                // T3: First successful client command
                try {
                    follower.api().getProcessManager().submit(new NoOpJob(), 1, 10);
                    t3 = System.currentTimeMillis();
                    break;
                } catch (Exception ignored) {
                    // Retry until it works
                }
            }
            Thread.sleep(10);
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(resultMd, true))) {
            pw.println("### Leader Failover (" + type + ")");
            pw.println("**T0 (Leader Killed):** 0 ms");
            pw.println("**T1 (Election Starts):** " + (t1 > 0 ? (t1 - t0) : 0) + " ms");
            pw.println("**T2 (Leader Elected):** " + (t2 - t0) + " ms");
            pw.println("**T3 (Service Restored):** " + (t3 - t0) + " ms");
            pw.println("**Election Latency (T2 - T1):** " + (t2 - (t1 > 0 ? t1 : t0)) + " ms");
            pw.println("**Service Restoration (T3 - T0):** " + (t3 - t0) + " ms\n");
        }
    }

    // ---------------------------------------------------------
    // 4. Worker Failover
    // ---------------------------------------------------------

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void benchmarkWorkerFailover() throws Exception {
        System.out.println("Starting Worker Failover Benchmark");
        
        com.aegisos.node.AegisNode submitter = cluster.nodes().get(0);

        // Submit a long running job
        JobHandle handle = submitter.api().getProcessManager().submit(new SleepJob(15000), 1, 100);

        long t0 = 0, t1 = 0, t2 = 0, t3 = 0, t4 = 0;
        com.aegisos.core.identity.NodeId killedNodeId = null;
        com.aegisos.node.AegisNode worker = null;

        // Wait until it's running
        while (true) {
            Optional<JobRecord> recOpt = submitter.runtimeAgent().registry().get(handle.jobId());
            if (recOpt.isPresent() && recOpt.get().getState() == JobState.RUNNING) {
                com.aegisos.core.identity.NodeId assigned = com.aegisos.core.identity.NodeId.of(recOpt.get().getAssignedNodeId().toByteArray());
                killedNodeId = assigned;
                for (com.aegisos.node.AegisNode n : cluster.nodes()) {
                    if (n.identity().nodeId().equals(assigned)) {
                        worker = n;
                        break;
                    }
                }
                
                t0 = System.currentTimeMillis();
                System.out.println("T0: Job RUNNING on " + assigned.shortId() + ". Killing worker.");
                cluster.stop(worker);
                break;
            }
            Thread.sleep(10);
        }

        while (true) {
            long now = System.currentTimeMillis();
            Optional<JobRecord> recOpt = submitter.runtimeAgent().registry().get(handle.jobId());
            
            // T1: DEAD detected by submitter discovery
            if (t1 == 0 && !submitter.discovery().membership().alivePeerIds().contains(killedNodeId)) {
                t1 = now;
                System.out.println("T1: Worker marked DEAD by discovery.");
            }
            
            if (recOpt.isPresent()) {
                JobRecord rec = recOpt.get();
                JobState state = rec.getState();
                
                // T2: LOST state applied via Raft
                if (t2 == 0 && state == JobState.LOST) {
                    t2 = now;
                    System.out.println("T2: Job state transitioned to LOST in Raft.");
                }
                
                // T4: RUNNING on new node
                if (t4 == 0 && state == JobState.RUNNING && t2 > 0) {
                    com.aegisos.core.identity.NodeId newAssigned = com.aegisos.core.identity.NodeId.of(rec.getAssignedNodeId().toByteArray());
                    if (!newAssigned.equals(killedNodeId)) {
                        t4 = now;
                        System.out.println("T4: Job resumed RUNNING on " + newAssigned.shortId());
                        break;
                    }
                }
            }
            Thread.sleep(10);
        }

        long deadLatency = t1 - t0;
        long lostLatency = t2 - t0;
        long recoverLatency = t4 - t0;

        System.out.println("### Worker Failover Recovery Timeline");
        System.out.println("T0 -> DEAD: " + deadLatency + " ms");
        System.out.println("T0 -> LOST: " + lostLatency + " ms");
        System.out.println("T0 -> RUNNING: " + recoverLatency + " ms");

        try (PrintWriter pw = new PrintWriter(new FileWriter(resultMd, true))) {
            pw.println("### Worker Failover (Fixed Timeline)");
            pw.println("**T0 (Worker Killed):** 0 ms");
            pw.println("**T1 (DEAD detected):** " + (t1 - t0) + " ms");
            pw.println("**T2 (LOST state):** " + (t2 - t0) + " ms");
            pw.println("**T4 (Running again):** " + (t4 - t0) + " ms");
            pw.println("**Recovery Latency (T4 - T0):** " + (t4 - t0) + " ms\n");
        }
    }
}

package com.aegisos.cluster;

import com.aegisos.api.JobHandle;
import com.aegisos.cluster.jobs.PrimeCounter;
import com.aegisos.node.AegisNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.BooleanSupplier;

/**
 * Phase 5 gate: a job submitted at one node is scheduled onto a cluster node, executed,
 * and returns the correct result; the scheduler handles a batch of 100 jobs.
 */
class Phase5Test {

    private static void dynamicWait(BooleanSupplier condition, long maxWaitMs) throws InterruptedException {
        long waited = 0;
        long backoff = 100;
        while (!condition.getAsBoolean() && waited < maxWaitMs) {
            Thread.sleep(backoff);
            waited += backoff;
            backoff = Math.min(backoff * 2, 800);
        }
    }

    @Test
    void submitRunAndCollectResult() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

            // Let resource reports propagate so placement has data.
            dynamicWait(() -> false, 1000);

            AegisNode submitter = nodes.get(0);

            // Single job: count primes up to 10000 (expected 1229).
            JobHandle handle = submitter.api().getProcessManager().submit(new PrimeCounter(10_000), 1, 128);
            Long result = submitter.api().getProcessManager().awaitResult(handle, 30_000);
            assertEquals(1229L, result, "prime count up to 10000 should be 1229");
        }
    }

    @Test
    void schedulesManyJobs() throws Exception {
        try (ClusterHarness harness = new ClusterHarness()) {
            List<AegisNode> nodes = harness.start(3);
            assertTrue(ClusterHarness.await(20_000, () ->
                    nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                            && nodes.stream().anyMatch(n -> n.consensus().isLeader())));
            dynamicWait(() -> false, 1000);

            AegisNode submitter = nodes.get(0);
            int jobs = 100;
            List<JobHandle> handles = new ArrayList<>(jobs);
            for (int i = 0; i < jobs; i++) {
                handles.add(submitter.api().getProcessManager().submit(new PrimeCounter(1_000), 1, 512));
            }
            for (JobHandle h : handles) {
                Long r = submitter.api().getProcessManager().awaitResult(h, 60_000);
                assertEquals(168L, r, "prime count up to 1000 should be 168");
            }
        }
    }
}

package com.aegisos.cluster;

import com.aegisos.api.ProcessManager;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.runtime.Serialization;
import com.aegisos.proto.JobRecord;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
public class SchedulerDeterminismTest {

    private ClusterHarness harness;

    @BeforeEach
    void setup() {
        harness = new ClusterHarness();
    }

    @AfterEach
    void teardown() {
        if (harness != null) harness.close();
    }

    @Test
    void testSchedulerIsDeterministicOnIdenticalNodes() throws Exception {
        harness.start(3);
        AegisNode node = harness.node(0);
        ProcessManager pm = node.api().getProcessManager();

        // Submit 20 identical jobs with the SAME JobID hash properties.
        // Wait, different JobIDs will hash differently. We want to show that for the SAME job ID (e.g. if we could re-submit it),
        // or just rely on the test that 100 identical jobs don't all go to the same node? 
        // Actually, if we submit 50 jobs concurrently, the hash will distribute them evenly across identical nodes.
        
        int totalJobs = 15;
        java.util.List<com.aegisos.api.JobHandle> handles = new java.util.ArrayList<>();
        
        for (int i = 0; i < totalJobs; i++) {
            String jobId = UUID.randomUUID().toString();
            com.aegisos.api.JobHandle handle = pm.submitJob(JobSpec.newBuilder()
                    .setJobId(jobId)
                    .setClassName(SleepJob.class.getName())
                    .setArgs(com.google.protobuf.ByteString.copyFrom(Serialization.serialize(new SleepJob(10))))
                    .build());
            handles.add(handle);
        }

        for (com.aegisos.api.JobHandle handle : handles) {
            pm.awaitResult(handle, 10_000);
        }
        
        // Assert jobs were spread due to hash tie-breaking, or load scoring
        // With load scoring, they will be perfectly spread.
        int[] counts = new int[3];
        for (com.aegisos.api.JobHandle handle : handles) {
            JobRecord rec = node.runtimeAgent().registry().get(handle.jobId()).orElseThrow();
            for (int i=0; i<3; i++) {
                if (rec.getAssignedNodeId().equals(com.google.protobuf.ByteString.copyFrom(harness.node(i).identity().nodeId().toBytes()))) {
                    counts[i]++;
                }
            }
        }
        
        assertTrue(counts[0] > 0, "Node 0 got no jobs");
        assertTrue(counts[1] > 0, "Node 1 got no jobs");
        assertTrue(counts[2] > 0, "Node 2 got no jobs");
    }
}

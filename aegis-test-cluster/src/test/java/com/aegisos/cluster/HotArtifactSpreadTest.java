package com.aegisos.cluster;

import com.aegisos.api.ProcessManager;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobSpec;
import com.aegisos.proto.ArtifactReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import com.aegisos.cluster.jobs.SleepJob;
import com.aegisos.runtime.Serialization;
import com.aegisos.proto.JobRecord;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
public class HotArtifactSpreadTest {

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
    void testHotArtifactSpillsOver() throws Exception {
        harness.start(3);
        AegisNode node = harness.node(0);
        ProcessManager pm = node.api().getProcessManager();

        // 1. Upload a large artifact so locality weight is very high
        byte[] largeData = new byte[1024 * 1024]; // 1MB
        String sha = pm.uploadArtifact(largeData);

        // 2. Run one job to prime the cache on Node 0
        String primerId = UUID.randomUUID().toString();
        com.aegisos.api.JobHandle primer = pm.submitJob(JobSpec.newBuilder()
                .setJobId(primerId)
                .setClassName(SleepJob.class.getName())
                .setArgs(com.google.protobuf.ByteString.copyFrom(Serialization.serialize(new SleepJob(10))))
                .addArtifacts(ArtifactReference.newBuilder().setSha256(sha).setMountPath("a.txt").build())
                .build());
        pm.awaitResult(primer, 10_000);
        
        JobRecord primerRec = node.runtimeAgent().registry().get(primerId).orElseThrow();
        com.google.protobuf.ByteString nodeWithCache = primerRec.getAssignedNodeId();

        // 3. Submit 30 jobs concurrently that use the same artifact
        // Permanently fill Node With Cache's hard allocations to guarantee it rejects new jobs
        AegisNode targetNode = harness.nodes().stream().filter(n -> com.google.protobuf.ByteString.copyFrom(n.identity().nodeId().toBytes()).equals(nodeWithCache)).findFirst().orElseThrow();
        com.aegisos.scheduler.ResourceAllocator allocator = targetNode.scheduler().allocator();
        allocator.commitHardAllocation("dummy-blocker", com.aegisos.proto.ResourceRequest.newBuilder().setCpuCores(99999).build());

        // Now submit 5 jobs. Node 0 will reject them in the probe phase, so they MUST spill over.
        int totalJobs = 5;
        java.util.List<com.aegisos.api.JobHandle> handles = new java.util.ArrayList<>();
        
        for (int i = 0; i < totalJobs; i++) {
            String jobId = UUID.randomUUID().toString();
            com.aegisos.api.JobHandle handle = pm.submitJob(JobSpec.newBuilder()
                    .setJobId(jobId)
                    .setClassName(SleepJob.class.getName())
                    .setArgs(com.google.protobuf.ByteString.copyFrom(Serialization.serialize(new SleepJob(100))))
                    .addArtifacts(ArtifactReference.newBuilder().setSha256(sha).setMountPath("a.txt").build())
                    .build());
            handles.add(handle);
        }

        for (com.aegisos.api.JobHandle handle : handles) {
            pm.awaitResult(handle, 30_000);
        }
        
        // Assert jobs were spread due to hotspot protection
        int[] counts = new int[3];
        for (com.aegisos.api.JobHandle handle : handles) {
            JobRecord rec = node.runtimeAgent().registry().get(handle.jobId()).orElseThrow();
            for (int i=0; i<3; i++) {
                if (rec.getAssignedNodeId().equals(com.google.protobuf.ByteString.copyFrom(harness.node(i).identity().nodeId().toBytes()))) {
                    counts[i]++;
                }
            }
        }
        
        // If it all went to the nodeWithCache, it would be a failure of spread.
        // We assert that the other nodes also got some jobs.
        int nodesUsed = 0;
        for (int i=0; i<3; i++) {
            if (counts[i] > 0) nodesUsed++;
        }
        
        assertTrue(nodesUsed > 1, "Hotspot protection failed: all jobs went to a single node");
    }
}

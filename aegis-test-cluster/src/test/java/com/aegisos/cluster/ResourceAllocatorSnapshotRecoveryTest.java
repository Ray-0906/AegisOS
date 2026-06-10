package com.aegisos.cluster;

import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobSpec;
import com.aegisos.proto.JobState;
import com.aegisos.proto.ResourceRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

public class ResourceAllocatorSnapshotRecoveryTest {

    @Test
    @DisplayName("ResourceAllocator correctly rehydrates hard allocations from JobRegistry after snapshot restart")
    void allocatorRecoversFromSnapshot() throws Exception {
        try (ClusterHarness cluster = new ClusterHarness()) {
            List<com.aegisos.node.AegisNode> nodes = cluster.start(3);
            com.aegisos.node.AegisNode leader = null;
            for (int attempt = 0; attempt < 200; attempt++) {
                for (com.aegisos.node.AegisNode n : nodes) if (n.consensus().isLeader()) leader = n;
                if (leader != null) break;
                Thread.sleep(50);
            }
            Assertions.assertNotNull(leader);

            // 1. Submit a job that will consume resources
            String jobId = UUID.randomUUID().toString();
            JobSpec spec = JobSpec.newBuilder()
                    .setJobId(jobId)
                    .setResources(ResourceRequest.newBuilder().setCpuCores(2).setMemoryMb(1024).build())
                    .build();

            // Direct injection to bypass scheduler logic, simulating a RUNNING job
            JobRecord runningJob = JobRecord.newBuilder()
                    .setSpec(spec)
                    .setState(JobState.RUNNING)
                    .setExecutionId(1)
                    .build();

            leader.consensus().propose(com.aegisos.proto.StateCommand.newBuilder()
                    .setType(com.aegisos.proto.CommandType.ASSIGN_JOB)
                    .setPayload(runningJob.toByteString())
                    .build());

            // Wait for it to apply
            Thread.sleep(1000);

            // Verify leader's Allocator didn't actually hard allocate because we bypassed the scheduler?
            // Actually, we bypassed the scheduler, so the ResourceAllocator isn't aware of it BEFORE restart either!
            // Wait, if we want to simulate the real world, we should either submit via API or manually inject into allocator.
            leader.resourceAllocator().commitHardAllocation(jobId, spec.getResources());
            Assertions.assertEquals(2, leader.resourceAllocator().hardAllocatedCpu());

            // 2. Trigger snapshot on all nodes to compact log
            for (com.aegisos.node.AegisNode n : nodes) {
                n.consensus().raftNode().triggerSnapshot();
            }
            Thread.sleep(1000);

            // 3. Stop and Restart Follower
            final com.aegisos.node.AegisNode finalLeader = leader;
            com.aegisos.node.AegisNode follower = nodes.stream()
                    .filter(n -> !n.identity().nodeId().equals(finalLeader.identity().nodeId()))
                    .findFirst().get();

            cluster.stop(follower);
            
            // 4. Start it back up
            com.aegisos.node.AegisNode restartedNode = cluster.restartNode(follower);

            // 5. Verify the restarted node has hydrated the ResourceAllocator!
            ClusterHarness.await(5000, () -> restartedNode.consensus().raftNode().commitIndex() > 0);
            
            Assertions.assertEquals(2, restartedNode.resourceAllocator().hardAllocatedCpu(), 
                "ResourceAllocator must reflect the 2 allocated CPUs from the restored JobRegistry snapshot");
            Assertions.assertEquals(1024, restartedNode.resourceAllocator().hardAllocatedMem(),
                "ResourceAllocator must reflect the 1024 allocated MB from the restored JobRegistry snapshot");
        }
    }
}

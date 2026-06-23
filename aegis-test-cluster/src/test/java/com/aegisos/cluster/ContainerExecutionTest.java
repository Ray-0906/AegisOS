package com.aegisos.cluster;

import com.aegisos.core.identity.NodeId;
import com.aegisos.proto.JobSpec;
import com.aegisos.proto.JobState;
import com.aegisos.proto.RuntimeType;
import com.aegisos.proto.ContainerSpec;
import com.aegisos.runtime.container.MemoryImageRegistry;
import com.aegisos.runtime.container.MockContainerEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ContainerExecutionTest {

    private static final Logger log = LoggerFactory.getLogger(ContainerExecutionTest.class);

    private ClusterHarness harness;
    private MockContainerEngine mockEngine;

    @BeforeEach
    void setUp() throws Exception {
        harness = new ClusterHarness();
        java.util.List<com.aegisos.node.AegisNode> nodes = harness.start(3);
        
        assertTrue(ClusterHarness.await(20_000, () ->
                nodes.stream().allMatch(n -> n.discovery().membership().aliveCount() == 3)
                        && nodes.stream().anyMatch(n -> n.consensus().isLeader())));

        mockEngine = new MockContainerEngine();
        for (com.aegisos.node.AegisNode node : nodes) {
            ((MemoryImageRegistry) node.runtimeAgent().getImageRegistry()).trust("alpine:latest");
            node.runtimeAgent().setContainerEngine(mockEngine);
        }
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void testContainerExecutionLifecycle() throws Exception {
        String jobId = UUID.randomUUID().toString();
        
        JobSpec spec = JobSpec.newBuilder()
                .setJobId(jobId)
                .setRuntime(RuntimeType.RUNTIME_CONTAINER)
                .setContainer(ContainerSpec.newBuilder().setImage("alpine:latest").build())
                .build();

        com.aegisos.node.AegisNode submitter = harness.nodes().get(0);
        
        submitter.api().getProcessManager().submitJob(spec);
        
        // Wait for it to become RUNNING
        assertTrue(ClusterHarness.await(5_000, () -> {
            JobState state = submitter.api().getProcessManager().status(jobId);
            return state == JobState.RUNNING;
        }), "Job should become RUNNING");

        // Finish the mock container
        mockEngine.completeAll(0, "success".getBytes(), "err".getBytes());

        // Wait for it to become COMPLETED
        assertTrue(ClusterHarness.await(5_000, () -> {
            var rec = submitter.runtimeAgent().registry().get(jobId);
            return rec.isPresent() && rec.get().getState() == JobState.COMPLETED;
        }), "Job should become COMPLETED");

        // Verify result
        var rec = submitter.runtimeAgent().registry().get(jobId).get();
        assertEquals(0, rec.getResult().size()); // Container results don't have JVM serialized payloads
    }

    @Test
    void testContainerFailureLifecycle() throws Exception {
        String jobId = UUID.randomUUID().toString();
        
        JobSpec spec = JobSpec.newBuilder()
                .setJobId(jobId)
                .setRuntime(RuntimeType.RUNTIME_CONTAINER)
                .setContainer(ContainerSpec.newBuilder().setImage("alpine:latest").build())
                .build();

        com.aegisos.node.AegisNode submitter = harness.nodes().get(0);
        submitter.api().getProcessManager().submitJob(spec);
        
        assertTrue(ClusterHarness.await(5_000, () -> submitter.api().getProcessManager().status(jobId) == JobState.RUNNING));

        mockEngine.completeAll(1, "fail".getBytes(), "err".getBytes());

        assertTrue(ClusterHarness.await(5_000, () -> submitter.api().getProcessManager().status(jobId) == JobState.FAILED));
        var rec = submitter.runtimeAgent().registry().get(jobId).get();
        assertNotNull(rec.getError());
    }

    @Test
    void testContainerCancellationLifecycle() throws Exception {
        String jobId = UUID.randomUUID().toString();
        
        JobSpec spec = JobSpec.newBuilder()
                .setJobId(jobId)
                .setRuntime(RuntimeType.RUNTIME_CONTAINER)
                .setContainer(ContainerSpec.newBuilder().setImage("alpine:latest").build())
                .build();

        com.aegisos.node.AegisNode submitter = harness.nodes().get(0);
        submitter.api().getProcessManager().submitJob(spec);
        
        assertTrue(ClusterHarness.await(5_000, () -> submitter.api().getProcessManager().status(jobId) == JobState.RUNNING));

        // Find the node running it and kill it
        for (com.aegisos.node.AegisNode node : harness.nodes()) {
            if (node.runtimeAgent().registry().get(jobId).map(r -> r.getState() == JobState.RUNNING).orElse(false)) {
                // If it is running on this node, its handle is there.
                node.runtimeAgent().cancelJob(jobId);
                break;
            }
        }

        assertTrue(ClusterHarness.await(5_000, () -> submitter.api().getProcessManager().status(jobId) == JobState.CANCELLED));
    }

    @Test
    void testContainerTrustPolicy() throws Exception {
        String jobId = UUID.randomUUID().toString();
        
        JobSpec spec = JobSpec.newBuilder()
                .setJobId(jobId)
                .setRuntime(RuntimeType.RUNTIME_CONTAINER)
                .setContainer(ContainerSpec.newBuilder().setImage("malicious:latest").build())
                .build();

        com.aegisos.node.AegisNode submitter = harness.nodes().get(0);
        submitter.api().getProcessManager().submitJob(spec);
        
        // It should fail quickly because malicious:latest is not trusted
        assertTrue(ClusterHarness.await(5_000, () -> submitter.api().getProcessManager().status(jobId) == JobState.FAILED));
        var rec = submitter.runtimeAgent().registry().get(jobId).get();
        assertTrue(rec.getError().toLowerCase().contains("untrusted"));
    }
    @Test
    void testContainerMigration() throws Exception {
        System.setProperty("aegis.lease.duration.ms", "15000");
        try {
            String jobId = UUID.randomUUID().toString();
            JobSpec spec = JobSpec.newBuilder()
                    .setJobId(jobId)
                    .setRuntime(RuntimeType.RUNTIME_CONTAINER)
                    .setContainer(ContainerSpec.newBuilder().setImage("alpine:latest").build())
                    .build();

            com.aegisos.node.AegisNode submitter = harness.nodes().get(0);
            submitter.api().getProcessManager().submitJob(spec);
            
            assertTrue(ClusterHarness.await(5_000, () -> submitter.api().getProcessManager().status(jobId) == JobState.RUNNING));

            // Find the node running it
            NodeId executorId = submitter.runtimeAgent().registry().get(jobId)
                    .map(com.aegisos.proto.JobRecord::getAssignedNodeId)
                    .filter(b -> !b.isEmpty())
                    .map(b -> NodeId.of(b.toByteArray())).orElseThrow();

            com.aegisos.node.AegisNode executor = harness.nodes().stream()
                    .filter(n -> n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();
                    
            // Kill the executor
            harness.stop(executor);

            com.aegisos.node.AegisNode aliveNode = harness.nodes().stream()
                    .filter(n -> !n.identity().nodeId().equals(executorId))
                    .findFirst()
                    .orElseThrow();

            // Wait for lease to expire and job to be migrated (executionId >= 2)
            assertTrue(ClusterHarness.await(45_000, () -> {
                return aliveNode.runtimeAgent().registry().get(jobId)
                    .map(r -> r.getExecutionId() >= 2 && (r.getState() == JobState.RUNNING || r.getState() == JobState.QUEUED))
                    .orElse(false);
            }), "Container job should be migrated to a new node");
            
            aliveNode.runtimeAgent().cancelJob(jobId);
        } finally {
            System.clearProperty("aegis.lease.duration.ms");
        }
    }

    // NOTE: Full cluster restart recovery for container jobs is NOT tested here.
    //
    // After a full cluster restart:
    //   - The Docker container process is destroyed
    //   - ExecutionHandle (runtimeId, containerId) is in-memory only — gone
    //   - DockerRuntimeBackend.activeContainers map is gone
    //
    // The JobSupervisor WILL detect the stale RUNNING state (via lease expiry)
    // and re-dispatch the job to a new container from scratch. But that is
    // re-execution, not recovery. There is no persistent runtime ownership yet.
    //
    // v1.1 guarantees:
    //   ✅ Single node failure → migration via lease expiry
    //   ✅ Worker partition → migration via lease expiry
    //   ❌ Full cluster restart while container is running → NOT supported
    //   ✅ Full cluster restart after container completed → supported (Raft state)
    //
    // Persistent runtime ownership is a v1.2 feature.
}

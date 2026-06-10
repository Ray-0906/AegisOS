package com.aegisos.cluster;

import com.aegisos.cluster.jobs.SleepJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class WorkspaceProvisioningTest {

    private ClusterHarness cluster;

    @BeforeEach
    public void setup() throws Exception {
        cluster = new ClusterHarness();
        cluster.start(3);
    }

    @AfterEach
    public void teardown() throws Exception {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    public void testWorkspaceDirectoriesCreated() throws Exception {
        String jobId = cluster.node(0).api().getProcessManager().submitJob(
                com.aegisos.proto.JobSpec.newBuilder()
                        .setJobId(java.util.UUID.randomUUID().toString())
                        .setClassName(SleepJob.class.getName())
                        .setArgs(com.google.protobuf.ByteString.copyFrom(com.aegisos.runtime.Serialization.serialize(new SleepJob(100))))
                        .setResources(com.aegisos.proto.ResourceRequest.newBuilder().setCpuCores(1).setMemoryMb(128).build())
                        .build()
        ).jobId();
        com.aegisos.api.JobHandle handle = new com.aegisos.api.JobHandle(jobId);
        cluster.node(0).api().getProcessManager().awaitResult(handle, 10_000);

        // Find which node executed the job
        com.aegisos.proto.JobRecord record = cluster.node(0).runtimeAgent().registry().get(jobId).orElseThrow();
        long executionId = record.getExecutionId();
        byte[] assignedNodeId = record.getAssignedNodeId().toByteArray();

        Path workspaceRoot = null;
        for (int i = 0; i < 3; i++) {
            if (java.util.Arrays.equals(cluster.node(i).identity().nodeId().toBytes(), assignedNodeId)) {
                workspaceRoot = cluster.node(i).config().workspaceDir();
                break;
            }
        }
        
        assertNotNull(workspaceRoot);
        Path execRoot = workspaceRoot.resolve(jobId).resolve("exec-" + executionId);
        
        // Assert directories exist
        assertTrue(Files.exists(execRoot.resolve("artifacts")));
        assertTrue(Files.exists(execRoot.resolve("scratch")));
        assertTrue(Files.exists(execRoot.resolve("checkpoints")));
        
        // Output files may not exist since stdout/stderr only written if pb.redirect happens
        // but we can check if job_args.bin exists or result.bin
        assertTrue(Files.exists(execRoot.resolve("job_args.bin")));
        assertTrue(Files.exists(execRoot.resolve("result.bin")));
    }
}

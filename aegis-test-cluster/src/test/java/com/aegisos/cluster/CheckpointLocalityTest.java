package com.aegisos.cluster;

import com.aegisos.api.ProcessManager;
import com.aegisos.node.AegisNode;
import com.aegisos.proto.JobSpec;
import com.aegisos.proto.ArtifactReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import com.aegisos.cluster.jobs.CheckpointableSum;
import com.aegisos.runtime.Serialization;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
public class CheckpointLocalityTest {

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
    void testRecoveredJobSchedulesOnCheckpointNode() throws Exception {
        harness.setReplicationFactor(1);
        harness.start(3);
        AegisNode node = harness.node(0);
        ProcessManager pm = node.api().getProcessManager();

        // 1. Submit a checkpointing job
        String jobId = UUID.randomUUID().toString();
        com.aegisos.api.JobHandle handle = pm.submitJob(JobSpec.newBuilder()
                .setJobId(jobId)
                .setClassName(CheckpointableSum.class.getName())
                .setArgs(com.google.protobuf.ByteString.copyFrom(Serialization.serialize(new CheckpointableSum(1000, 100L))))
                .build());

        // Wait until it creates at least 1 checkpoint
        long deadline = System.currentTimeMillis() + 20_000;
        JobRecord rec = null;
        while (System.currentTimeMillis() < deadline) {
            rec = node.runtimeAgent().registry().get(jobId).orElse(null);
            if (rec != null && rec.getState() == JobState.RUNNING && !rec.getCheckpointFileId().isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        
        assertNotNull(rec, "Job never started or checkpointed");
        assertFalse(rec.getCheckpointFileId().isEmpty(), "No checkpoint created");

        // The node that was executing it
        com.google.protobuf.ByteString origNodeId = rec.getAssignedNodeId();
        
        // Let's force a reschedule. To do this, we can cancel the job locally but NOT cluster-wide (or emit LOST directly)
        // Wait, emitting LOST state will trigger the JobSupervisor to requeue it.
        // Or we can just kill the node that was running it! But if we kill the node, that node won't be available to schedule on!
        // The point of the test is that if the node is ALIVE but the job crashed, it gets rescheduled there.
        // Wait, if the job crashes, we don't restart it automatically unless it's LOST.
        // Let's manually emit a LOST state for the job so JobSupervisor requeues it.
        com.aegisos.proto.JobUpdate lostUpdate = com.aegisos.proto.JobUpdate.newBuilder()
                .setJobId(jobId)
                .setExecutionId(rec.getExecutionId())
                .setState(JobState.LOST)
                .build();
        node.consensus().propose(com.aegisos.proto.StateCommand.newBuilder()
                .setType(com.aegisos.proto.CommandType.UPDATE_JOB)
                .setPayload(lostUpdate.toByteString())
                .build()).get();

        // Wait for it to be rescheduled
        deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            rec = node.runtimeAgent().registry().get(jobId).orElseThrow();
            if (rec.getExecutionId() == 2 && rec.getState() == JobState.RUNNING) {
                break;
            }
            Thread.sleep(100);
        }
        
        assertEquals(2, rec.getExecutionId(), "Job was not requeued");
        
        // Find which node AegisFS actually placed the checkpoint replica on
        String fileId = rec.getCheckpointFileId();
        com.aegisos.proto.FileMetadata meta = node.api().getFileSystem().fileIndex().byName(fileId).orElseThrow();
        com.google.protobuf.ByteString checkpointNodeId = meta.getChunks(0).getNodeIds(0);

        // Assert it was assigned to THAT node because of checkpoint locality
        assertEquals(checkpointNodeId, rec.getAssignedNodeId(), 
            "Scheduler failed to place recovered job on the node with the checkpoint");
    }
}

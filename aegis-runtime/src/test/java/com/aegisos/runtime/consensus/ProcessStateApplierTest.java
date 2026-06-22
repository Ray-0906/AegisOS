package com.aegisos.runtime.consensus;

import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.proto.ProcessRecordProto;
import com.aegisos.proto.ProcessResourcesProto;
import com.aegisos.proto.ProcessStateProto;
import com.aegisos.runtime.table.InMemoryProcessTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessStateApplierTest {

    private InMemoryProcessTable processTable;
    private ProcessStateApplier applier;

    @BeforeEach
    void setUp() {
        processTable = new InMemoryProcessTable();
        applier = new ProcessStateApplier(processTable);
    }

    @Test
    void testApplySubmit() {
        ProcessRecordProto proto = ProcessRecordProto.newBuilder()
                .setProcessId("proc-123")
                .setArtifactId("art-456")
                .setOwnerNodeId("node-A")
                .setExecutionId(1L)
                .setState(ProcessStateProto.PROCESS_QUEUED)
                .setResources(ProcessResourcesProto.newBuilder().setCpuCores(2).setMemoryMb(1024).build())
                .setSubmitTimestamp(1000L)
                .setStateTimestamp(1000L)
                .build();

        applier.applySubmit(proto.toByteArray());

        Optional<ProcessRecord> opt = processTable.lookup("proc-123");
        assertTrue(opt.isPresent(), "Process should be in the table");
        ProcessRecord record = opt.get();
        assertEquals("proc-123", record.processId());
        assertEquals("art-456", record.artifactId());
        assertEquals("node-A", record.ownerNodeId());
        assertEquals(1L, record.executionId());
        assertEquals(ProcessState.QUEUED, record.state());
        assertEquals(2, record.resources().cpuCores());
        assertEquals(1024L, record.resources().memoryMb());
    }

    @Test
    void testApplyUpdate() {
        // Setup initial
        ProcessRecordProto initial = ProcessRecordProto.newBuilder()
                .setProcessId("proc-123")
                .setState(ProcessStateProto.PROCESS_QUEUED)
                .build();
        applier.applySubmit(initial.toByteArray());

        // Update
        ProcessRecordProto update = ProcessRecordProto.newBuilder()
                .setProcessId("proc-123")
                .setOwnerNodeId("node-B")
                .setExecutionId(2L)
                .setState(ProcessStateProto.PROCESS_RUNNING)
                .setStateTimestamp(2000L)
                .build();

        applier.applyUpdate(update.toByteArray());

        Optional<ProcessRecord> opt = processTable.lookup("proc-123");
        assertTrue(opt.isPresent());
        ProcessRecord record = opt.get();
        assertEquals(ProcessState.RUNNING, record.state());
        assertEquals("node-B", record.ownerNodeId());
        assertEquals(2L, record.executionId());
        assertEquals(2000L, record.stateTimestamp());
    }

    @Test
    void testApplyCancel() {
        // Setup initial
        ProcessRecordProto initial = ProcessRecordProto.newBuilder()
                .setProcessId("proc-123")
                .setState(ProcessStateProto.PROCESS_RUNNING)
                .setOwnerNodeId("node-B")
                .setExecutionId(2L)
                .build();
        applier.applySubmit(initial.toByteArray());

        // Cancel (only requires processId, others optional or empty)
        ProcessRecordProto cancel = ProcessRecordProto.newBuilder()
                .setProcessId("proc-123")
                .setOwnerNodeId("node-B")
                .setExecutionId(2L)
                .build();

        applier.applyCancel(cancel.toByteArray());

        Optional<ProcessRecord> opt = processTable.lookup("proc-123");
        assertTrue(opt.isPresent());
        ProcessRecord record = opt.get();
        assertEquals(ProcessState.CANCELLED, record.state());
    }
}

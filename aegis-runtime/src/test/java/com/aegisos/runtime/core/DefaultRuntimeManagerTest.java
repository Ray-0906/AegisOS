package com.aegisos.runtime.core;

import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessResources;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.api.runtime.ProcessTable;
import com.aegisos.api.runtime.ProcessScheduler;
import com.aegisos.api.runtime.RuntimeEngine;
import com.aegisos.api.runtime.RuntimeManager;
import com.aegisos.runtime.table.InMemoryProcessTable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DefaultRuntimeManagerTest {

    private RuntimeManager runtimeManager;

    @BeforeEach
    public void setup() {
        ProcessTable processTable = new InMemoryProcessTable();
        ProcessScheduler processScheduler = new SimpleProcessScheduler();
        RuntimeEngine runtimeEngine = new LocalRuntimeEngine();
        runtimeManager = new DefaultRuntimeManager(processTable, processScheduler, runtimeEngine);
    }

    @Test
    public void testSubmitProcessLifecycle() {
        ProcessResources resources = new ProcessResources(2, 1024L);
        String processId = runtimeManager.submitProcess("test-artifact-id", resources);

        ProcessRecord record = runtimeManager.getProcessDetails(processId);

        assertNotNull(record);
        assertEquals(ProcessState.RUNNING, record.state());
        assertEquals("local-node", record.ownerNodeId());
    }

    @Test
    public void testCancelProcess() {
        ProcessResources resources = new ProcessResources(1, 512L);
        String processId = runtimeManager.submitProcess("test-artifact-id", resources);

        runtimeManager.cancelProcess(processId);

        ProcessRecord record = runtimeManager.getProcessDetails(processId);

        assertNotNull(record);
        assertEquals(ProcessState.CANCELLED, record.state());
    }
}

package com.aegisos.runtime.core;

import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessResources;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.api.runtime.ProcessTable;
import com.aegisos.api.runtime.ProcessScheduler;
import com.aegisos.api.runtime.RuntimeEngine;
import com.aegisos.api.runtime.RuntimeManager;
import com.aegisos.runtime.table.InMemoryProcessTable;
import com.aegisos.consensus.ConsensusModule;
import com.aegisos.proto.StateCommand;
import com.aegisos.runtime.consensus.ProcessStateApplier;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.identity.IdentityService;
import com.aegisos.network.NetworkLayer;
import com.aegisos.core.observability.MetricsRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class DefaultRuntimeManagerTest {

    private RuntimeManager runtimeManager;
    private ProcessTable processTable;
    private ProcessStateApplier applier;

    @BeforeEach
    public void setup() throws Exception {
        processTable = new InMemoryProcessTable();
        ProcessScheduler processScheduler = new SimpleProcessScheduler();
        RuntimeEngine runtimeEngine = new LocalRuntimeEngine();
        applier = new ProcessStateApplier(processTable);

        IdentityService identity = IdentityService.ephemeral();
        NodeId localNode = identity.nodeId();
        NetworkLayer network = new NetworkLayer(identity, 0, "127.0.0.1");

        Path tempDir = Files.createTempDirectory("raft-test");
        ConsensusModule consensus = new ConsensusModule(
            network,
            localNode, 
            tempDir,
            Collections::emptyList, 
            Collections::emptyList, 
            () -> true, 
            true, 
            10, 
            (id) -> null,
            new MetricsRegistry()
        ) {
            @Override
            public CompletableFuture<Long> propose(StateCommand command) {
                switch (command.getType()) {
                    case SUBMIT_PROCESS:
                        applier.applySubmit(command.getPayload().toByteArray());
                        break;
                    case UPDATE_PROCESS_STATE:
                        applier.applyUpdate(command.getPayload().toByteArray());
                        break;
                    case CANCEL_PROCESS:
                        applier.applyCancel(command.getPayload().toByteArray());
                        break;
                    default:
                        break;
                }
                return CompletableFuture.completedFuture(1L);
            }
        };

        runtimeManager = new DefaultRuntimeManager(processTable, processScheduler, runtimeEngine, consensus);
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

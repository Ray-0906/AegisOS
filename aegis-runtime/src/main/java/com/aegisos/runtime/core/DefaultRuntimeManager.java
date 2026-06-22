package com.aegisos.runtime.core;

import com.aegisos.api.runtime.PlacementDecision;
import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessResources;
import com.aegisos.api.runtime.ProcessScheduler;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.api.runtime.ProcessTable;
import com.aegisos.api.runtime.RuntimeEngine;
import com.aegisos.api.runtime.RuntimeManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DefaultRuntimeManager implements RuntimeManager {

    private final ProcessTable processTable;
    private final ProcessScheduler processScheduler;
    private final RuntimeEngine runtimeEngine;

    public DefaultRuntimeManager(ProcessTable processTable, ProcessScheduler processScheduler, RuntimeEngine runtimeEngine) {
        this.processTable = processTable;
        this.processScheduler = processScheduler;
        this.runtimeEngine = runtimeEngine;
    }

    @Override
    public String submitProcess(String artifactId, ProcessResources resources) {
        String processId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        ProcessRecord record = new ProcessRecord(
                processId,
                artifactId,
                null,
                0,
                ProcessState.SUBMITTED,
                resources,
                now,
                now
        );

        processTable.register(record);

        PlacementDecision decision = processScheduler.evaluate(record);

        processTable.updateState(processId, ProcessState.PLACED, System.currentTimeMillis(), decision.assignedNodeId(), 0);

        runtimeEngine.start(record);

        processTable.updateState(processId, ProcessState.RUNNING, System.currentTimeMillis(), decision.assignedNodeId(), 0);

        return processId;
    }

    @Override
    public void cancelProcess(String processId) {
        Optional<ProcessRecord> optionalRecord = processTable.lookup(processId);
        if (optionalRecord.isPresent()) {
            runtimeEngine.stop(processId);
            processTable.updateState(
                    processId,
                    ProcessState.CANCELLED,
                    System.currentTimeMillis(),
                    optionalRecord.get().ownerNodeId(),
                    optionalRecord.get().executionId()
            );
        }
    }

    @Override
    public ProcessRecord getProcessDetails(String processId) {
        return processTable.lookup(processId).orElse(null);
    }

    @Override
    public List<ProcessRecord> listProcesses() {
        return processTable.list();
    }
}

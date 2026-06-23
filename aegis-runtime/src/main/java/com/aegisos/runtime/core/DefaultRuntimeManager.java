package com.aegisos.runtime.core;

import com.aegisos.api.runtime.PlacementDecision;
import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessResources;
import com.aegisos.api.runtime.ProcessScheduler;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.api.runtime.ProcessTable;
import com.aegisos.api.runtime.RuntimeEngine;
import com.aegisos.api.runtime.RuntimeManager;
import com.aegisos.consensus.ConsensusModule;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.ProcessRecordProto;
import com.aegisos.proto.StateCommand;
import com.aegisos.runtime.util.ProcessMapper;
import com.aegisos.core.identity.IdentityService;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class DefaultRuntimeManager implements RuntimeManager {

    private final ProcessTable processTable;
    private final ProcessScheduler processScheduler;
    private final RuntimeEngine runtimeEngine;
    private final ConsensusModule consensus;
    private final IdentityService identity;

    public DefaultRuntimeManager(ProcessTable processTable, ProcessScheduler processScheduler, RuntimeEngine runtimeEngine, ConsensusModule consensus, IdentityService identity) {
        this.processTable = processTable;
        this.processScheduler = processScheduler;
        this.runtimeEngine = runtimeEngine;
        this.consensus = consensus;
        this.identity = identity;
    }

    private void propose(CommandType type, ProcessRecord record) {
        try {
            ProcessRecordProto proto = ProcessMapper.toProto(record);
            StateCommand cmd = StateCommand.newBuilder()
                    .setType(type)
                    .setPayload(proto.toByteString())
                    .build();
            consensus.propose(cmd).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to propose process state to Raft", e);
        }
    }

    @Override
    public String submitProcess(String artifactId, ProcessResources resources) {
        String processId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();

        ProcessRecord record = new ProcessRecord(
                processId,
                artifactId,
                null,
                identity.nodeId().toHex(),
                0,
                ProcessState.SUBMITTED,
                resources,
                now,
                now
        );

        propose(CommandType.SUBMIT_PROCESS, record);

        return processId;
    }

    @Override
    public void cancelProcess(String processId) {
        Optional<ProcessRecord> optionalRecord = processTable.lookup(processId);
        if (optionalRecord.isPresent()) {
            ProcessRecord existing = optionalRecord.get();
            ProcessRecord cancelledRecord = new ProcessRecord(
                    processId,
                    existing.artifactId(),
                    existing.ownerNodeId(),
                    existing.submitterNodeId(),
                    existing.executionId(),
                    ProcessState.CANCELLED,
                    existing.resources(),
                    existing.submitTimestamp(),
                    System.currentTimeMillis()
            );

            propose(CommandType.CANCEL_PROCESS, cancelledRecord);
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

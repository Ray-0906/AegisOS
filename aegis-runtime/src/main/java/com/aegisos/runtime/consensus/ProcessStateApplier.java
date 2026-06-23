package com.aegisos.runtime.consensus;

import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.api.runtime.ProcessTable;
import com.aegisos.proto.ProcessRecordProto;
import com.aegisos.runtime.util.ProcessMapper;

public class ProcessStateApplier {

    private final ProcessTable processTable;

    public ProcessStateApplier(ProcessTable processTable) {
        this.processTable = processTable;
    }

    public void applySubmit(byte[] payload) {
        try {
            ProcessRecordProto proto = ProcessRecordProto.parseFrom(payload);
            ProcessRecord record = ProcessMapper.fromProto(proto);
            if (record != null) {
                processTable.register(record);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void applyUpdate(byte[] payload) {
        try {
            ProcessRecordProto proto = ProcessRecordProto.parseFrom(payload);
            ProcessRecord record = ProcessMapper.fromProto(proto);
            if (record != null && record.processId() != null) {
                processTable.updateState(
                        record.processId(),
                        record.state(),
                        record.stateTimestamp(),
                        record.ownerNodeId(),
                        record.executionId()
                );
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void applyCancel(byte[] payload) {
        try {
            ProcessRecordProto proto = ProcessRecordProto.parseFrom(payload);
            String processId = proto.getProcessId();
            if (processId != null && !processId.isEmpty()) {
                long now = System.currentTimeMillis();
                String owner = proto.getOwnerNodeId().isEmpty() ? null : proto.getOwnerNodeId();
                processTable.updateState(processId, ProcessState.CANCELLED, now, owner, proto.getExecutionId());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void applyCheckpoint(byte[] payload) {
        try {
            com.aegisos.proto.CheckpointStateProto proto = com.aegisos.proto.CheckpointStateProto.parseFrom(payload);
            processTable.saveCheckpoint(proto.getProcessId(), proto.getStateData().toByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

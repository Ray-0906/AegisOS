package com.aegisos.runtime.util;

import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessResources;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.proto.ProcessRecordProto;
import com.aegisos.proto.ProcessResourcesProto;
import com.aegisos.proto.ProcessStateProto;

public final class ProcessMapper {

    private ProcessMapper() {}

    public static ProcessStateProto toProto(ProcessState state) {
        if (state == null) {
            return ProcessStateProto.PROCESS_SUBMITTED;
        }
        return switch (state) {
            case SUBMITTED -> ProcessStateProto.PROCESS_SUBMITTED;
            case QUEUED -> ProcessStateProto.PROCESS_QUEUED;
            case PLACED -> ProcessStateProto.PROCESS_PLACED;
            case STARTING -> ProcessStateProto.PROCESS_STARTING;
            case RUNNING -> ProcessStateProto.PROCESS_RUNNING;
            case CHECKPOINTING -> ProcessStateProto.PROCESS_CHECKPOINTING;
            case PAUSED -> ProcessStateProto.PROCESS_PAUSED;
            case MIGRATING -> ProcessStateProto.PROCESS_MIGRATING;
            case COMPLETED -> ProcessStateProto.PROCESS_COMPLETED;
            case FAILED -> ProcessStateProto.PROCESS_FAILED;
            case CANCELLED -> ProcessStateProto.PROCESS_CANCELLED;
        };
    }

    public static ProcessState fromProto(ProcessStateProto proto) {
        if (proto == null) {
            return ProcessState.SUBMITTED;
        }
        return switch (proto) {
            case PROCESS_SUBMITTED, UNRECOGNIZED -> ProcessState.SUBMITTED;
            case PROCESS_QUEUED -> ProcessState.QUEUED;
            case PROCESS_PLACED -> ProcessState.PLACED;
            case PROCESS_STARTING -> ProcessState.STARTING;
            case PROCESS_RUNNING -> ProcessState.RUNNING;
            case PROCESS_CHECKPOINTING -> ProcessState.CHECKPOINTING;
            case PROCESS_PAUSED -> ProcessState.PAUSED;
            case PROCESS_MIGRATING -> ProcessState.MIGRATING;
            case PROCESS_COMPLETED -> ProcessState.COMPLETED;
            case PROCESS_FAILED -> ProcessState.FAILED;
            case PROCESS_CANCELLED -> ProcessState.CANCELLED;
        };
    }

    public static ProcessResourcesProto toProto(ProcessResources resources) {
        if (resources == null) {
            return ProcessResourcesProto.getDefaultInstance();
        }
        return ProcessResourcesProto.newBuilder()
                .setCpuCores(resources.cpuCores())
                .setMemoryMb(resources.memoryMb())
                .build();
    }

    public static ProcessResources fromProto(ProcessResourcesProto proto) {
        if (proto == null) {
            return null;
        }
        return new ProcessResources(proto.getCpuCores(), proto.getMemoryMb());
    }

    public static ProcessRecordProto toProto(ProcessRecord record) {
        if (record == null) {
            return ProcessRecordProto.getDefaultInstance();
        }
        return ProcessRecordProto.newBuilder()
                .setProcessId(record.processId() == null ? "" : record.processId())
                .setArtifactId(record.artifactId() == null ? "" : record.artifactId())
                .setOwnerNodeId(record.ownerNodeId() == null ? "" : record.ownerNodeId())
                .setExecutionId(record.executionId())
                .setState(toProto(record.state()))
                .setResources(toProto(record.resources()))
                .setSubmitTimestamp(record.submitTimestamp())
                .setStateTimestamp(record.stateTimestamp())
                .build();
    }

    public static ProcessRecord fromProto(ProcessRecordProto proto) {
        if (proto == null) {
            return null;
        }
        return new ProcessRecord(
                proto.getProcessId().isEmpty() ? null : proto.getProcessId(),
                proto.getArtifactId().isEmpty() ? null : proto.getArtifactId(),
                proto.getOwnerNodeId().isEmpty() ? null : proto.getOwnerNodeId(),
                proto.getExecutionId(),
                fromProto(proto.getState()),
                fromProto(proto.getResources()),
                proto.getSubmitTimestamp(),
                proto.getStateTimestamp()
        );
    }
}

package com.aegisos.runtime.core;

import com.aegisos.api.runtime.PipelineRecord;
import com.aegisos.api.runtime.PipelineStatus;
import com.aegisos.api.runtime.PipelineTable;
import com.aegisos.api.runtime.ProcessTable;
import com.aegisos.consensus.SnapshotException;
import com.aegisos.consensus.SnapshotParticipant;
import com.aegisos.proto.PipelineRecordProto;
import com.aegisos.proto.PipelineStatusProto;
import com.aegisos.proto.ProcessRecordProto;
import com.aegisos.proto.SnapshotProto;
import com.aegisos.runtime.util.ProcessMapper;

public class RuntimeSnapshotParticipant implements SnapshotParticipant {

    private final ProcessTable processTable;
    private final PipelineTable pipelineTable;

    public RuntimeSnapshotParticipant(ProcessTable processTable, PipelineTable pipelineTable) {
        this.processTable = processTable;
        this.pipelineTable = pipelineTable;
    }

    @Override
    public String id() {
        return "runtime-engine";
    }

    @Override
    public byte[] snapshot() throws SnapshotException {
        try {
            SnapshotProto.Builder builder = SnapshotProto.newBuilder();

            for (var process : processTable.list()) {
                builder.addProcesses(ProcessMapper.toProto(process));
            }

            for (PipelineRecord pipeline : pipelineTable.getAll()) {
                PipelineRecordProto.Builder pipeBuilder = PipelineRecordProto.newBuilder()
                        .setPipelineId(pipeline.pipelineId())
                        .addAllProcessIds(pipeline.processIds());

                if (pipeline.status() != null) {
                    pipeBuilder.setStatus(PipelineStatusProto.valueOf("PIPELINE_" + pipeline.status().name()));
                }

                builder.addPipelines(pipeBuilder.build());
            }

            return builder.build().toByteArray();
        } catch (Exception e) {
            throw new SnapshotException("Failed to create runtime snapshot", e);
        }
    }

    @Override
    public void restore(byte[] data) throws SnapshotException {
        try {
            SnapshotProto proto = SnapshotProto.parseFrom(data);

            for (ProcessRecordProto processProto : proto.getProcessesList()) {
                processTable.put(ProcessMapper.fromProto(processProto));
            }

            for (PipelineRecordProto pipeProto : proto.getPipelinesList()) {
                PipelineStatus status = PipelineStatus.PENDING;
                if (pipeProto.getStatus() != null && pipeProto.getStatus() != PipelineStatusProto.UNRECOGNIZED) {
                    String statusStr = pipeProto.getStatus().name();
                    if (statusStr.startsWith("PIPELINE_")) {
                        status = PipelineStatus.valueOf(statusStr.substring(9));
                    }
                }
                pipelineTable.put(new PipelineRecord(pipeProto.getPipelineId(), pipeProto.getProcessIdsList(), status));
            }
        } catch (Exception e) {
            throw new SnapshotException("Failed to restore runtime snapshot", e);
        }
    }
}

package com.aegisos.runtime;

import com.aegisos.proto.CheckpointMetadata;

public record JobCheckpointRecord(
    String jobId,
    long executionId,
    String checkpointFileId,
    CheckpointMetadata metadata
) {
}

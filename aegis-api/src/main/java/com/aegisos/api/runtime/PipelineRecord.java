package com.aegisos.api.runtime;

import java.util.List;

public record PipelineRecord(
    String pipelineId,
    List<String> processIds,
    PipelineStatus status
) {}

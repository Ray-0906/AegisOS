package com.aegisos.api.runtime;

import java.util.Optional;

public interface PipelineTable {
    void put(PipelineRecord record);
    Optional<PipelineRecord> get(String pipelineId);
    Iterable<PipelineRecord> getAll();
}

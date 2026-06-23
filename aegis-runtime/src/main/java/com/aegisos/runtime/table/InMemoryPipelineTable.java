package com.aegisos.runtime.table;

import com.aegisos.api.runtime.PipelineRecord;
import com.aegisos.api.runtime.PipelineTable;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryPipelineTable implements PipelineTable {
    private final ConcurrentMap<String, PipelineRecord> table = new ConcurrentHashMap<>();

    @Override
    public void put(PipelineRecord record) {
        table.put(record.pipelineId(), record);
    }

    @Override
    public Optional<PipelineRecord> get(String pipelineId) {
        return Optional.ofNullable(table.get(pipelineId));
    }

    @Override
    public Iterable<PipelineRecord> getAll() {
        return table.values();
    }
}

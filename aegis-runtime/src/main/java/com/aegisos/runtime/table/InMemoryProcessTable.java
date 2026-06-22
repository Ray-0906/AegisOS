package com.aegisos.runtime.table;

import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.api.runtime.ProcessTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryProcessTable implements ProcessTable {

    private final ConcurrentHashMap<String, ProcessRecord> table = new ConcurrentHashMap<>();

    @Override
    public void register(ProcessRecord record) {
        if (table.putIfAbsent(record.processId(), record) != null) {
            throw new IllegalArgumentException("Process already exists: " + record.processId());
        }
    }

    @Override
    public void updateState(String processId, ProcessState state, long stateTimestamp, String ownerNodeId, long executionId) {
        table.computeIfPresent(processId, (id, existing) -> new ProcessRecord(
                existing.processId(),
                existing.artifactId(),
                ownerNodeId,
                executionId,
                state,
                existing.resources(),
                existing.submitTimestamp(),
                stateTimestamp
        ));
    }

    @Override
    public Optional<ProcessRecord> lookup(String processId) {
        return Optional.ofNullable(table.get(processId));
    }

    @Override
    public List<ProcessRecord> list() {
        return new ArrayList<>(table.values());
    }

    @Override
    public void remove(String processId) {
        table.remove(processId);
    }
}

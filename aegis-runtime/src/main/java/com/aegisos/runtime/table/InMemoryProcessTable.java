package com.aegisos.runtime.table;

import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.api.runtime.ProcessStateListener;
import com.aegisos.api.runtime.ProcessTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class InMemoryProcessTable implements ProcessTable {

    private final ConcurrentHashMap<String, ProcessRecord> table = new ConcurrentHashMap<>();
    private final java.util.Map<String, byte[]> checkpoints = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ProcessStateListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void addListener(ProcessStateListener listener) {
        listeners.add(listener);
    }

    private void notifyListeners(ProcessRecord record) {
        eventExecutor.submit(() -> {
            for (ProcessStateListener listener : listeners) {
                try {
                    listener.onProcessStateChanged(record);
                } catch (Exception e) {
                    // Ignore listener errors to prevent blocking the event loop
                }
            }
        });
    }

    @Override
    public void register(ProcessRecord record) {
        if (table.putIfAbsent(record.processId(), record) != null) {
            throw new IllegalArgumentException("Process already exists: " + record.processId());
        }
        notifyListeners(record);
    }

    @Override
    public void updateState(String processId, ProcessState state, long stateTimestamp, String ownerNodeId, long executionId) {
        ProcessRecord updated = table.computeIfPresent(processId, (id, existing) -> new ProcessRecord(
                existing.processId(),
                existing.artifactId(),
                ownerNodeId,
                existing.submitterNodeId(),
                executionId,
                state,
                existing.resources(),
                existing.submitTimestamp(),
                stateTimestamp,
                existing.executionCommand(),
                existing.pipeToProcessId()
        ));
        if (updated != null) {
            notifyListeners(updated);
        }
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

    @Override
    public void saveCheckpoint(String processId, byte[] stateData) {
        checkpoints.put(processId, stateData);
    }

    @Override
    public byte[] getLatestCheckpoint(String processId) {
        return checkpoints.get(processId);
    }
}

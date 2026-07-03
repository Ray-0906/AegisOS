package com.aegisos.runtime.table;

import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.api.runtime.ProcessStateListener;
import com.aegisos.api.runtime.ProcessTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class InMemoryProcessTable implements ProcessTable, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(InMemoryProcessTable.class);
    private static final long RETENTION_MS = TimeUnit.HOURS.toMillis(24);

    private final ConcurrentHashMap<String, ProcessRecord> table = new ConcurrentHashMap<>();
    private final java.util.Map<String, byte[]> checkpoints = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<ProcessStateListener> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scavenger = Executors.newSingleThreadScheduledExecutor();

    public InMemoryProcessTable() {
        scavenger.scheduleAtFixedRate(this::evictExpiredProcesses, 1, 1, TimeUnit.HOURS);
    }

    private void evictExpiredProcesses() {
        long now = System.currentTimeMillis();
        for (ProcessRecord record : table.values()) {
            if ((record.state() == ProcessState.COMPLETED || record.state() == ProcessState.FAILED) &&
                (now - record.stateTimestamp() > RETENTION_MS)) {
                table.remove(record.processId());
                log.info("Evicted expired process {} from memory (State: {})", record.processId(), record.state());
            }
        }
    }

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
    public void put(ProcessRecord record) {
        table.put(record.processId(), record);
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
                existing.pipeToProcessId(),
                existing.resourceConstraints(),
                existing.placementConstraints(),
                existing.serviceName(),
                existing.pipeToService(),
                existing.traceId()
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
    public Optional<ProcessRecord> lookupService(String serviceName) {
        return table.values().stream()
                .filter(r -> r.serviceName() != null && r.serviceName().equals(serviceName))
                .filter(r -> r.state() == ProcessState.RUNNING || r.state() == ProcessState.PLACED)
                .findFirst();
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

    public byte[] takeSnapshot() {
        com.aegisos.proto.ProcessTableSnapshotProto.Builder builder = com.aegisos.proto.ProcessTableSnapshotProto.newBuilder();
        for (ProcessRecord record : table.values()) {
            builder.addRecords(com.aegisos.runtime.util.ProcessMapper.toProto(record));
        }
        return builder.build().toByteArray();
    }

    public void installSnapshot(byte[] data) {
        try {
            com.aegisos.proto.ProcessTableSnapshotProto snapshot = com.aegisos.proto.ProcessTableSnapshotProto.parseFrom(data);
            table.clear();
            for (com.aegisos.proto.ProcessRecordProto proto : snapshot.getRecordsList()) {
                ProcessRecord record = com.aegisos.runtime.util.ProcessMapper.fromProto(proto);
                table.put(record.processId(), record);
            }
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            throw new RuntimeException("Failed to parse snapshot data", e);
        }
    }

    @Override
    public void close() {
        scavenger.shutdownNow();
        eventExecutor.shutdownNow();
    }
}

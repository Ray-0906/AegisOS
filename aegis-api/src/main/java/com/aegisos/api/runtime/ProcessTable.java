package com.aegisos.api.runtime;

import java.util.List;
import java.util.Optional;

public interface ProcessTable {
    void register(ProcessRecord record);
    void updateState(String processId, ProcessState state, long stateTimestamp, String ownerNodeId, long executionId);
    Optional<ProcessRecord> lookup(String processId);
    List<ProcessRecord> list();
    void remove(String processId);
    void addListener(ProcessStateListener listener);
    void saveCheckpoint(String processId, byte[] stateData);
    byte[] getLatestCheckpoint(String processId);
}

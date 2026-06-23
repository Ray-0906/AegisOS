package com.aegisos.api.runtime;

public interface RuntimeEngine {
    void start(ProcessRecord process);
    void stop(String processId);
    void pause(String processId);
    void checkpoint(String processId, byte[] stateData);
}

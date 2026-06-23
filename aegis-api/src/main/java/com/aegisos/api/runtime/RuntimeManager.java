package com.aegisos.api.runtime;

import java.util.List;

public interface RuntimeManager {
    String submitProcess(String artifactId, ProcessResources resources, String executionCommand, String pipeToProcessId);
    void cancelProcess(String processId);
    ProcessRecord getProcessDetails(String processId);
    List<ProcessRecord> listProcesses();
    void checkpoint(String processId, byte[] stateData);
}

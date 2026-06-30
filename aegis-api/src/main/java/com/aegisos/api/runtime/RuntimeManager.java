package com.aegisos.api.runtime;

import java.util.List;

public interface RuntimeManager {
    String submitProcess(String artifactId, ResourceConstraints resourceConstraints, PlacementConstraints placementConstraints, String executionCommand, String pipeToProcessId, String serviceName, String pipeToService, String traceId);
    void cancelProcess(String processId);
    ProcessRecord getProcessDetails(String processId);
    List<ProcessRecord> listProcesses();
    void checkpoint(String processId, byte[] stateData);
}

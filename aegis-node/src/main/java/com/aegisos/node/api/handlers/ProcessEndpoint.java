package com.aegisos.node.api.handlers;

import com.aegisos.api.runtime.RuntimeManager;
import com.aegisos.api.runtime.ProcessResources;
import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.node.api.ResponseWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public class ProcessEndpoint {
    private final RuntimeManager runtimeManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProcessEndpoint(RuntimeManager runtimeManager) {
        this.runtimeManager = runtimeManager;
    }

    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        try {
            if ("POST".equals(method) && "/v1/processes".equals(path)) {
                submitProcess(exchange);
            } else if ("GET".equals(method) && "/v1/processes".equals(path)) {
                listProcesses(exchange);
            } else if ("GET".equals(method) && path.startsWith("/v1/processes/")) {
                getProcessDetails(exchange, path.substring("/v1/processes/".length()));
            } else if ("DELETE".equals(method) && path.startsWith("/v1/processes/")) {
                cancelProcess(exchange, path.substring("/v1/processes/".length()));
            } else {
                ResponseWriter.writeError(exchange, 405, "METHOD_NOT_ALLOWED");
            }
        } catch (Exception e) {
            ResponseWriter.writeError(exchange, 500, "INTERNAL_SERVER_ERROR");
        }
    }

    private void submitProcess(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            JsonNode payload = mapper.readTree(is);
            String artifactId = payload.get("artifactId").asText();
            JsonNode resourcesNode = payload.get("resources");
            ProcessResources resources = new ProcessResources(
                    resourcesNode.get("cpuCores").asInt(),
                    resourcesNode.get("memoryMb").asLong()
            );

            String processId = runtimeManager.submitProcess(artifactId, resources);
            ResponseWriter.writeJson(exchange, 201, Map.of("processId", processId));
        }
    }

    private void listProcesses(HttpExchange exchange) throws IOException {
        List<ProcessRecord> processes = runtimeManager.listProcesses();
        ResponseWriter.writeJson(exchange, 200, processes);
    }

    private void getProcessDetails(HttpExchange exchange, String processId) throws IOException {
        ProcessRecord record = runtimeManager.getProcessDetails(processId);
        if (record == null) {
            ResponseWriter.writeError(exchange, 404, "PROCESS_NOT_FOUND");
        } else {
            ResponseWriter.writeJson(exchange, 200, record);
        }
    }

    private void cancelProcess(HttpExchange exchange, String processId) throws IOException {
        runtimeManager.cancelProcess(processId);
        ResponseWriter.writeJson(exchange, 202, Map.of());
    }
}

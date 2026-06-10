package com.aegisos.core.util;

import java.io.FileWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

public final class DebugLogger {
    private static final Path LOG_PATH = Path.of("C:", "Users", "astra", "Desktop", "projects", "AgeisOS", "debug-cc5e8f.log");
    private static final String SESSION_ID = "cc5e8f";

    public static void log(String location, String message, Map<String, Object> data, String hypothesisId, String runId) {
        try (FileWriter fw = new FileWriter(LOG_PATH.toFile(), true)) {
            long ts = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder();
            sb.append("{\"sessionId\":\"").append(SESSION_ID).append("\"");
            sb.append(",\"id\":\"log_").append(ts).append("_").append(UUID.randomUUID().toString().substring(0, 8)).append("\"");
            sb.append(",\"timestamp\":").append(ts);
            sb.append(",\"location\":\"").append(location).append("\"");
            sb.append(",\"message\":\"").append(message.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
            if (data != null && !data.isEmpty()) {
                sb.append(",\"data\":{");
                boolean first = true;
                for (var e : data.entrySet()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(e.getKey()).append("\":");
                    Object v = e.getValue();
                    if (v instanceof Number) sb.append(v);
                    else sb.append("\"").append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
                }
                sb.append("}");
            }
            sb.append(",\"runId\":\"").append(runId).append("\"");
            sb.append(",\"hypothesisId\":\"").append(hypothesisId).append("\"}");
            fw.write(sb.toString());
            fw.write("\n");
        } catch (Exception ignored) {}
    }
}

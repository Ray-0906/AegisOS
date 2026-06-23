package com.aegisos.node.api;

import com.aegisos.api.dto.common.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class ResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(ResponseWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void writeJson(HttpExchange exchange, int statusCode, Object body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public static void writeError(HttpExchange exchange, int statusCode, String message) throws IOException {
        log.debug("API Error {}: {}", statusCode, message);
        writeJson(exchange, statusCode, new ErrorResponse(message, statusCode));
    }

    public static void writeError(HttpExchange exchange, int statusCode, String message, String leaderId, Integer apiPort) throws IOException {
        log.debug("API Error {}: {} (Leader: {})", statusCode, message, leaderId);
        writeJson(exchange, statusCode, new ErrorResponse(message, statusCode, leaderId, apiPort));
    }

    public static void writeRedirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        byte[] msg = ("Redirecting to " + location).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(307, msg.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(msg);
        }
    }
}

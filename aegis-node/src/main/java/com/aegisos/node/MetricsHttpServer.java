package com.aegisos.node;

import com.aegisos.core.observability.MetricsExporter;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class MetricsHttpServer {
    
    private final MetricsExporter exporter;

    public MetricsHttpServer(MetricsExporter exporter) {
        this.exporter = exporter;
    }

    public void register(HttpServer server) {
        server.createContext("/metrics", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = exporter.export().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
    }
}

package com.aegisos.core.model;

import java.util.Objects;

/** An {@code ip:port} network endpoint. */
public record Endpoint(String host, int port) {

    public Endpoint {
        Objects.requireNonNull(host, "host");
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("invalid port: " + port);
        }
    }

    public static Endpoint parse(String hostPort) {
        int idx = hostPort.lastIndexOf(':');
        if (idx < 0) {
            throw new IllegalArgumentException("expected host:port, got " + hostPort);
        }
        String host = hostPort.substring(0, idx).trim();
        int port = Integer.parseInt(hostPort.substring(idx + 1).trim());
        return new Endpoint(host, port);
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}

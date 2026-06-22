package com.aegisos.node.api;

import com.aegisos.node.AegisNode;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ApiServer {

    private static final Logger log = LoggerFactory.getLogger(ApiServer.class);

    private final AegisNode node;
    private final int port;
    private HttpServer server;
    private ExecutorService executor;

    public ApiServer(AegisNode node, int port) {
        this.node = node;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 10);
        server.createContext("/v1/", new Router(node));
        
        executor = Executors.newFixedThreadPool(10, r -> {
            Thread t = new Thread(r, "aegis-api-" + node.identity().nodeId().shortId());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.start();

        log.info("REST API server listening on http://0.0.0.0:{}/v1/", boundPort());
    }

    public void shutdown() {
        if (server != null) {
            server.stop(1);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        log.info("REST API server shut down");
    }

    public int boundPort() {
        return server != null ? server.getAddress().getPort() : port;
    }
}

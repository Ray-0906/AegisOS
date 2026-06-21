package com.aegisos.node.api;

import com.aegisos.node.AegisNode;
import com.aegisos.node.api.handlers.AdminHandler;
import com.aegisos.node.api.handlers.ClusterHandler;
import com.aegisos.node.api.handlers.FileHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class Router implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(Router.class);

    private final ClusterHandler clusterHandler;
    private final AdminHandler adminHandler;
    private final FileHandler fileHandler;

    public Router(AegisNode node) {
        this.clusterHandler = new ClusterHandler(node);
        this.adminHandler = new AdminHandler(node);
        this.fileHandler = new FileHandler(node);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();

        log.debug("API Request: {} {}", method, path);

        try {
            if (method.equals("GET")) {
                if (path.equals("/v1/health")) {
                    clusterHandler.getHealth(exchange);
                } else if (path.equals("/v1/nodes")) {
                    clusterHandler.getNodes(exchange);
                } else if (path.equals("/v1/leader")) {
                    clusterHandler.getLeader(exchange);
                } else if (path.startsWith("/v1/files/")) {
                    fileHandler.getFile(exchange, path.substring("/v1/files/".length()));
                } else {
                    ResponseWriter.writeError(exchange, 404, "Not Found");
                }
            } else if (method.equals("PUT")) {
                if (path.startsWith("/v1/files/")) {
                    fileHandler.putFile(exchange, path.substring("/v1/files/".length()));
                } else {
                    ResponseWriter.writeError(exchange, 404, "Not Found");
                }
            } else if (method.equals("POST")) {
                if (path.equals("/v1/admin/membership")) {
                    adminHandler.addVoter(exchange);
                } else {
                    ResponseWriter.writeError(exchange, 404, "Not Found");
                }
            } else if (method.equals("DELETE")) {
                if (path.startsWith("/v1/admin/membership/")) {
                    adminHandler.removeVoter(exchange, path.substring("/v1/admin/membership/".length()));
                } else {
                    ResponseWriter.writeError(exchange, 404, "Not Found");
                }
            } else {
                ResponseWriter.writeError(exchange, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            log.error("Internal API Error handling {} {}", method, path, e);
            ResponseWriter.writeError(exchange, 500, "Internal Server Error: " + e.getMessage());
        } finally {
            exchange.close();
        }
    }
}

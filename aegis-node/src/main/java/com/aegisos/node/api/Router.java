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
    private final com.aegisos.node.api.handlers.JobHandler jobHandler;
    private final com.aegisos.node.api.handlers.ArtifactHandler artifactHandler;

    public Router(AegisNode node) {
        this.clusterHandler = new ClusterHandler(node);
        this.adminHandler = new AdminHandler(node);
        this.fileHandler = new FileHandler(node);
        this.jobHandler = new com.aegisos.node.api.handlers.JobHandler(node);
        this.artifactHandler = new com.aegisos.node.api.handlers.ArtifactHandler(node);
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
                    String subPath = path.substring("/v1/files/".length());
                    if (subPath.isEmpty()) {
                        fileHandler.listFiles(exchange, "/");
                    } else {
                        fileHandler.getFile(exchange, subPath);
                    }
                } else if (path.equals("/v1/files")) {
                    fileHandler.listFiles(exchange, "/");
                } else if (path.equals("/v1/artifacts")) {
                    artifactHandler.listArtifacts(exchange);
                } else if (path.equals("/v1/jobs")) {
                    jobHandler.listJobs(exchange);
                } else if (path.startsWith("/v1/jobs/")) {
                    String subPath = path.substring("/v1/jobs/".length());
                    // format: {id} or {id}/logs
                    int slashIdx = subPath.indexOf('/');
                    if (slashIdx == -1) {
                        jobHandler.getJob(exchange, subPath);
                    } else {
                        String jobId = subPath.substring(0, slashIdx);
                        String action = subPath.substring(slashIdx + 1);
                        if (action.equals("logs")) {
                            // parse query param for stream and executionId
                            String query = exchange.getRequestURI().getQuery();
                            String stream = "stdout";
                            Long execId = null;
                            if (query != null) {
                                for (String param : query.split("&")) {
                                    String[] kv = param.split("=");
                                    if (kv.length == 2) {
                                        if (kv[0].equals("stream")) stream = kv[1];
                                        if (kv[0].equals("executionId")) execId = Long.parseLong(kv[1]);
                                    }
                                }
                            }
                            if (!stream.equals("stdout") && !stream.equals("stderr")) {
                                stream = "stdout"; // default or throw error
                            }
                            jobHandler.getJobLogs(exchange, jobId, stream, execId);
                        } else {
                            ResponseWriter.writeError(exchange, 404, "Not Found");
                        }
                    }
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
                } else if (path.equals("/v1/artifacts")) {
                    // Extract ?name=foo.jar from query params
                    String query = exchange.getRequestURI().getQuery();
                    String name = "artifact.jar"; // default
                    if (query != null) {
                        for (String param : query.split("&")) {
                            String[] kv = param.split("=");
                            if (kv.length == 2 && kv[0].equals("name")) {
                                name = kv[1];
                            }
                        }
                    }
                    artifactHandler.uploadArtifact(exchange, name);
                } else if (path.equals("/v1/jobs")) {
                    jobHandler.submitJob(exchange);
                } else {
                    ResponseWriter.writeError(exchange, 404, "Not Found");
                }
            } else if (method.equals("DELETE")) {
                if (path.startsWith("/v1/admin/membership/")) {
                    adminHandler.removeVoter(exchange, path.substring("/v1/admin/membership/".length()));
                } else if (path.startsWith("/v1/jobs/")) {
                    jobHandler.cancelJob(exchange, path.substring("/v1/jobs/".length()));
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

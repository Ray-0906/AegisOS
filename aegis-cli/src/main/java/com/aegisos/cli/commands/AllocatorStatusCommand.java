package com.aegisos.cli.commands;

import picocli.CommandLine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@CommandLine.Command(
        name = "status",
        description = "Dump resource allocator status"
)
public class AllocatorStatusCommand implements Runnable {

    @CommandLine.Option(names = "--host", defaultValue = "127.0.0.1", description = "Host of the node")
    private String host;

    @CommandLine.Option(names = "--api-port", defaultValue = "10001", description = "API port of the node (node1=10001, node2=10002, node3=10003)")
    private int apiPort;

    @Override
    public void run() {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + host + ":" + apiPort + "/allocator"))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                System.out.println(response.body());
            } else {
                System.err.println("Failed to get allocator status: HTTP " + response.statusCode());
            }
        } catch (Exception e) {
            System.err.println("Error connecting to node: " + e.getMessage());
        }
    }
}

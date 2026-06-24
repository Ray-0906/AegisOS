package com.aegisos.cli.commands;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Command(name = "rm", description = "Remove an artifact from the cluster", mixinStandardHelpOptions = true)
public class RmCommand implements Runnable {

    @Option(names = {"--seed"}, description = "Cluster seed node (ip:port)", defaultValue = "127.0.0.1:18000")
    String seed;

    @Parameters(index = "0", description = "The ID of the artifact to remove")
    String artifactId;

    @Override
    public void run() {
        try {
            String leaderUrl = "http://" + seed;

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(leaderUrl + "/v1/files/" + artifactId))
                    .DELETE()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 202) {
                System.out.println("Artifact removal accepted by cluster.");
            } else {
                System.err.println("Failed to remove artifact. Server returned " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception e) {
            System.err.println("Failed to communicate with cluster: " + e.getMessage());
        }
    }
}

package com.aegisos.cli.commands;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "logs", description = "Stream process logs")
public class LogsCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Parameters(index = "0", description = "Process ID")
    private String processId;

    @Option(names = {"-f", "--follow"}, description = "Follow log output")
    private boolean follow;

    @Override
    public Integer call() throws Exception {
        if (seeds.isEmpty()) {
            System.err.println("aegis logs: at least one --seed is required");
            return 2;
        }

        String seed = seeds.get(0);
        String[] parts = seed.split(":");
        String leaderIp = parts[0];
        String leaderPort = parts.length > 1 ? parts[1] : "18000";
        String leaderUrl = "http://" + leaderIp + ":" + leaderPort;

        String endpoint = leaderUrl + "/v1/processes/" + processId + "/logs";
        if (follow) {
            endpoint += "?follow=true";
        }

        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            System.err.println("Error: " + response.statusCode());
            return 1;
        }

        try (InputStream is = response.body()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                System.out.write(buffer, 0, read);
                System.out.flush();
            }
        }

        return 0;
    }
}

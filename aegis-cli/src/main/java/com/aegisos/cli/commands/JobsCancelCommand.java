package com.aegisos.cli.commands;

import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "cancel", description = "Cancel a running or queued job.")
public final class JobsCancelCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Job ID")
    String jobId;

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        if (seeds.isEmpty()) {
            System.err.println("aegis jobs cancel: at least one --seed is required");
            return 2;
        }
        try {
            List<URI> seedUris = seeds.stream().map(s -> URI.create("http://" + s)).toList();
            AegisClient client = new AegisClient(seedUris);
            client.cancelJob(jobId);
            System.out.println("Cancel requested for job " + jobId);
            return 0;
        } catch (Exception e) {
            System.err.println("aegis jobs cancel failed: " + e.getMessage());
            return 1;
        }
    }
}

package com.aegisos.cli.commands;

import com.aegisos.api.dto.job.JobSummary;
import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "list", description = "List all jobs in the cluster.")
public final class JobsListCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        if (seeds.isEmpty()) {
            System.err.println("aegis jobs list: at least one --seed is required");
            return 2;
        }
        try {
            List<URI> seedUris = seeds.stream().map(s -> URI.create("http://" + s)).toList();
            AegisClient client = new AegisClient(seedUris);
            List<JobSummary> jobs = client.listJobs();

            System.out.printf("%-36s %-12s %-14s %-6s %s%n", "JOB ID", "STATE", "NODE", "EXEC", "ERROR");
            for (JobSummary r : jobs) {
                String error = r.error() == null || r.error().isEmpty() ? "" : r.error();
                System.out.printf("%-36s %-12s %-14s %-6d %s%n",
                        r.id(), r.state(), r.node(), r.executionId(), error);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("aegis jobs list failed: " + e.getMessage());
            return 1;
        }
    }
}

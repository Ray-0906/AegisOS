package com.aegisos.cli.commands;

import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "logs", description = "Get stdout/stderr logs for a job.")
public final class JobsLogsCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Job ID")
    String jobId;

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @CommandLine.Option(names = "--execution", description = "Specific execution ID (defaults to latest).")
    Long executionId;

    @Override
    public Integer call() {
        if (seeds.isEmpty()) {
            System.err.println("aegis jobs logs: at least one --seed is required");
            return 2;
        }
        try {
            List<URI> seedUris = seeds.stream().map(s -> URI.create("http://" + s)).toList();
            AegisClient client = new AegisClient(seedUris);

            String execLabel = executionId != null ? String.valueOf(executionId) : "latest";
            
            System.out.println("=== STDOUT (execution " + execLabel + ") ===");
            try {
                String stdout = client.getJobLogs(jobId, "stdout", executionId);
                System.out.println(stdout);
            } catch (Exception e) {
                System.out.println("(no stdout available)");
            }

            System.out.println("=== STDERR (execution " + execLabel + ") ===");
            try {
                String stderr = client.getJobLogs(jobId, "stderr", executionId);
                System.out.println(stderr);
            } catch (Exception e) {
                System.out.println("(no stderr available)");
            }

            return 0;
        } catch (Exception e) {
            System.err.println("aegis jobs logs failed: " + e.getMessage());
            return 1;
        }
    }
}

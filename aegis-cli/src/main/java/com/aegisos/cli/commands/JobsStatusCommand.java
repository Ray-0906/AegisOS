package com.aegisos.cli.commands;

import com.aegisos.api.dto.job.JobDetails;
import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "status", description = "Get the status of a specific job.")
public final class JobsStatusCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Job ID")
    String jobId;

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        if (seeds.isEmpty()) {
            System.err.println("aegis status: at least one --seed is required");
            return 2;
        }
        try {
            List<URI> seedUris = seeds.stream().map(s -> URI.create("http://" + s)).toList();
            AegisClient client = new AegisClient(seedUris);
            JobDetails record = client.getJobStatus(jobId);

            if (record == null) {
                System.out.println("Job " + jobId + " not found.");
                return 0;
            }

            System.out.println("Job");
            System.out.println("---");
            System.out.println("ID:         " + record.id());
            System.out.println("State:      " + record.state());
            System.out.println("Execution:  " + record.executionId());
            System.out.println("Node:       " + record.node());
            
            if (record.error() != null && !record.error().isEmpty()) {
                System.out.println("Error:      " + record.error());
            }
            
            System.out.println("\nResources");
            System.out.println("---------");
            if (record.resources() != null) {
                System.out.println("CPU:        " + record.resources().cpu());
                System.out.println("Memory:     " + record.resources().memoryMb() + " MB");
            } else {
                System.out.println("CPU:        Unknown");
                System.out.println("Memory:     Unknown");
            }
            
            return 0;
        } catch (Exception e) {
            System.err.println("aegis status failed: " + e.getMessage());
            return 1;
        }
    }
}

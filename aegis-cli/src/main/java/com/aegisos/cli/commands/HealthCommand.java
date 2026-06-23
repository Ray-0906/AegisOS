package com.aegisos.cli.commands;

import com.aegisos.api.dto.cluster.HealthResponse;
import com.aegisos.cli.util.RestCliHelper;
import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "health", description = "Show the health status of the cluster subsystems.")
public final class HealthCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        if (seeds.isEmpty()) {
            System.err.println("aegis health: at least one --seed is required");
            return 2;
        }

        try {
            AegisClient client = RestCliHelper.createClient(seeds);
            HealthResponse health = client.getHealth();
            System.out.println(health.status);
            return 0;
        } catch (Exception e) {
            System.err.println("aegis health failed: " + e.getMessage());
            return 1;
        }
    }
}

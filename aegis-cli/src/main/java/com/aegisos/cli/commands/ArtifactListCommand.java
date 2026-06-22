package com.aegisos.cli.commands;

import com.aegisos.api.dto.artifact.ArtifactSummary;
import com.aegisos.cli.util.RestCliHelper;
import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "list", description = "List uploaded artifacts.")
public final class ArtifactListCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--seed", description = "Seed peer(s) to connect to.")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        if (seeds.isEmpty()) {
            System.err.println("aegis artifact list: at least one --seed is required");
            return 2;
        }

        try {
            AegisClient client = RestCliHelper.createClient(seeds);
            List<ArtifactSummary> artifacts = client.listArtifacts();
            System.out.printf("%-64s %-30s %12s%n", "ARTIFACT ID", "FILE NAME", "SIZE");
            for (ArtifactSummary r : artifacts) {
                System.out.printf("%-64s %-30s %12d%n", r.artifactId, r.name, r.sizeBytes);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("list failed: " + e.getMessage());
            return 1;
        }
    }
}

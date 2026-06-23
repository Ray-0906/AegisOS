package com.aegisos.cli.commands;

import com.aegisos.api.dto.cluster.NodeResponse;
import com.aegisos.cli.util.RestCliHelper;
import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "nodes", description = "List alive cluster nodes.")
public final class NodesCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    java.util.List<String> seeds = java.util.List.of();

    @Override
    public Integer call() {
        if (seeds.isEmpty()) {
            System.err.println("aegis nodes: at least one --seed is required");
            return 2;
        }

        try {
            AegisClient client = RestCliHelper.createClient(seeds);
            List<NodeResponse> nodes = client.getNodes();

            System.out.printf("%-14s %-22s %-8s%n", "NODE", "API_PORT", "STATUS");
            for (NodeResponse n : nodes) {
                // Shorten node id for display
                String id = n.nodeId;
                if (id != null && id.length() > 12) {
                    id = id.substring(0, 12);
                }
                System.out.printf("%-14s %-22s %-8s%n", id, n.apiPort, n.status);
            }
            return 0;

        } catch (Exception e) {
            System.err.println("aegis nodes failed: " + e.getMessage());
            return 1;
        }
    }
}

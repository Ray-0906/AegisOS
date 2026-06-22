package com.aegisos.cli.commands;

import com.aegisos.api.dto.cluster.HealthResponse;
import com.aegisos.api.dto.cluster.LeaderResponse;
import com.aegisos.api.dto.cluster.NodeResponse;
import com.aegisos.cli.util.RestCliHelper;
import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "cluster-health", description = "Show aggregated cluster health.")
public final class ClusterHealthCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        if (seeds.isEmpty()) {
            System.err.println("aegis cluster-health: at least one --seed is required");
            return 2;
        }

        try {
            AegisClient client = RestCliHelper.createClient(seeds);

            LeaderResponse leader = client.getLeader();
            List<NodeResponse> nodes = client.getNodes();
            HealthResponse health = client.getHealth();

            System.out.println("Cluster Health\n");
            
            String leaderId = leader.leaderId;
            if (leaderId != null && leaderId.length() > 12) {
                leaderId = leaderId.substring(0, 12);
            }
            System.out.println("Leader:\n" + leaderId + "\n");
            
            System.out.println("Nodes:\n");
            for (NodeResponse n : nodes) {
                String id = n.nodeId;
                if (id != null && id.length() > 12) {
                    id = id.substring(0, 12);
                }
                System.out.println(id + " " + n.status);
            }
            System.out.println();
            
            System.out.println("API:\n" + health.status);
            
            return 0;

        } catch (Exception e) {
            System.err.println("aegis cluster-health failed: " + e.getMessage());
            return 1;
        }
    }
}

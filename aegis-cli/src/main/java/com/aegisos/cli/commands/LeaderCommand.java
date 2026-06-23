package com.aegisos.cli.commands;

import com.aegisos.api.dto.cluster.LeaderResponse;
import com.aegisos.cli.util.RestCliHelper;
import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "leader", description = "Show the cluster leader.")
public final class LeaderCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        if (seeds.isEmpty()) {
            System.err.println("aegis leader: at least one --seed is required");
            return 2;
        }

        try {
            AegisClient client = RestCliHelper.createClient(seeds);
            LeaderResponse leader = client.getLeader();
            System.out.println(leader.leaderId);
            return 0;
        } catch (Exception e) {
            System.err.println("aegis leader failed: " + e.getMessage());
            return 1;
        }
    }
}

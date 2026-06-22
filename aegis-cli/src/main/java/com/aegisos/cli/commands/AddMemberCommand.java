package com.aegisos.cli.commands;

import com.aegisos.api.dto.membership.MembershipResponse;
import com.aegisos.cli.util.RestCliHelper;
import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "add-member", description = "Add a node as a voting member of the cluster.")
public final class AddMemberCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "The Node ID to add (hex format).")
    String targetNodeId;

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        if (seeds.isEmpty()) {
            System.err.println("aegis admin add-member: at least one --seed is required");
            return 2;
        }

        try {
            AegisClient client = RestCliHelper.createClient(seeds);
            MembershipResponse res = client.addMember(targetNodeId);
            System.out.println("Status: " + res.status);
            System.out.println("Message: " + res.message);
            return 0;
        } catch (Exception e) {
            System.err.println("aegis admin add-member failed: " + e.getMessage());
            return 1;
        }
    }
}

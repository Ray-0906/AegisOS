package com.aegisos.cli.commands;

import com.aegisos.api.dto.membership.MembershipResponse;
import com.aegisos.cli.util.RestCliHelper;
import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "remove-member", description = "Remove a node from the voting members of the cluster.")
public final class RemoveMemberCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "The Node ID to remove (hex format).")
    String targetNodeId;

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        if (seeds.isEmpty()) {
            System.err.println("aegis admin remove-member: at least one --seed is required");
            return 2;
        }

        try {
            AegisClient client = RestCliHelper.createClient(seeds);
            MembershipResponse res = client.removeMember(targetNodeId);
            System.out.println("Status: " + res.status);
            System.out.println("Message: " + res.message);
            return 0;
        } catch (Exception e) {
            System.err.println("aegis admin remove-member failed: " + e.getMessage());
            return 1;
        }
    }
}

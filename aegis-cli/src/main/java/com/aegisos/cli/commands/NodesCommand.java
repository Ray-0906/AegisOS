package com.aegisos.cli.commands;

import com.aegisos.cli.client.AegisClient;
import com.aegisos.proto.ClientQuery;
import com.aegisos.proto.ClientQueryResult;
import com.aegisos.proto.MembershipList;
import com.aegisos.proto.PeerEntry;
import com.aegisos.proto.QueryType;
import picocli.CommandLine;

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

        try (AegisClient client = new AegisClient()) {
            client.start();

            ClientQuery query = ClientQuery.newBuilder()
                    .setType(QueryType.LIST_NODES)
                    .build();

            ClientQueryResult result = client.query(seeds, query);

            if (!result.getError().isEmpty()) {
                System.err.println("Query failed: " + result.getError());
                return 1;
            }

            MembershipList list = MembershipList.parseFrom(result.getPayload());

            System.out.printf("%-14s %-22s %-8s%n", "NODE", "ADDRESS", "STATUS");
            for (PeerEntry p : list.getPeersList()) {
                String id = com.aegisos.core.util.HexUtil.shortId(p.getNodeId().toByteArray());
                System.out.printf("%-14s %-22s %-8s%n", id, p.getAddress(), p.getStatus());
            }
            return 0;

        } catch (Exception e) {
            System.err.println("aegis nodes failed: " + e.getMessage());
            return 1;
        }
    }
}

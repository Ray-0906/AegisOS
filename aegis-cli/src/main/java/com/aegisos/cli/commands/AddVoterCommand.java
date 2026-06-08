package com.aegisos.cli.commands;

import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "add-voter", description = "Add a node as a voting member of the Raft cluster.")
public final class AddVoterCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "The Node ID to add as a voter (hex format).")
    String targetNodeId;

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() throws Exception {
        return ClientCommands.runAddVoter(seeds, targetNodeId);
    }
}

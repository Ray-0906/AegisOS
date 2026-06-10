package com.aegisos.cli.commands;

import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "remove-voter", description = "Remove a node from the voting members of the Raft cluster.")
public final class RemoveVoterCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "The Node ID to remove (hex format).")
    String targetNodeId;

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() throws Exception {
        return ClientCommands.runRemoveVoter(seeds, targetNodeId);
    }
}

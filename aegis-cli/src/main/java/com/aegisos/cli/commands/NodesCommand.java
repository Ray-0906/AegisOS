package com.aegisos.cli.commands;

import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "nodes", description = "List alive cluster nodes.")
public final class NodesCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    java.util.List<String> seeds = java.util.List.of();

    @Override
    public Integer call() {
        // Wired in Phase 2 once discovery/membership is available via the client session.
        return ClientCommands.runNodes(seeds);
    }
}

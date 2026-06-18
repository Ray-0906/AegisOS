package com.aegisos.cli.commands;

import picocli.CommandLine;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "cluster", description = "Show the cluster overview.")
public final class ClusterCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        return ClientCommands.runCluster(seeds);
    }
}

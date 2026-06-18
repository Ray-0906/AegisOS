package com.aegisos.cli.commands;

import picocli.CommandLine;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "health", description = "Show the health status of the cluster subsystems.")
public final class HealthCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        return ClientCommands.runHealth(seeds);
    }
}

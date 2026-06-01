package com.aegisos.cli.commands;

import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "run", description = "Submit a job to the cluster.")
public final class RunCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Fully-qualified job class name.")
    String className;

    @CommandLine.Parameters(index = "1..*", arity = "0..*", description = "Job arguments.")
    List<String> args = new ArrayList<>();

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        return ClientCommands.runJob(seeds, className, args);
    }
}

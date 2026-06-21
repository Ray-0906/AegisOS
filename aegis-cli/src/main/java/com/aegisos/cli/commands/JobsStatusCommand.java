package com.aegisos.cli.commands;

import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "status", description = "Show the detailed status of a specific job.")
public final class JobsStatusCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Job id.")
    String jobId;

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        return ClientCommands.runStatus(seeds, jobId);
    }
}

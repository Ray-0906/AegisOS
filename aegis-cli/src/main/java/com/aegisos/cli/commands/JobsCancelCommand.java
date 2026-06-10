package com.aegisos.cli.commands;

import picocli.CommandLine;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "cancel", description = "Cancel a running job.")
public final class JobsCancelCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Job id.")
    String jobId;

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        return ClientCommands.runJobsCancel(seeds, jobId);
    }
}

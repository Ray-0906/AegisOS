package com.aegisos.cli.commands;

import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "jobs", description = "Job management commands.",
        subcommands = { JobsListCommand.class, JobsStatusCommand.class, JobsCancelCommand.class, JobsLogsCommand.class })
public final class JobsCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        JobsListCommand listCmd = new JobsListCommand();
        listCmd.seeds = this.seeds;
        return listCmd.call();
    }
}

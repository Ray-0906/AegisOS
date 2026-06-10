package com.aegisos.cli.commands;

import picocli.CommandLine;

@CommandLine.Command(name = "jobs", description = "Job management commands.",
        subcommands = { JobsListCommand.class, JobsCancelCommand.class, JobsLogsCommand.class })
public final class JobsCommand implements Runnable {
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}

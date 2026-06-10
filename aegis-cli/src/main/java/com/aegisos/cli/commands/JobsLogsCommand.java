package com.aegisos.cli.commands;

import picocli.CommandLine;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "logs", description = "Show logs of a job.")
public final class JobsLogsCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Job id.")
    String jobId;

    @CommandLine.Option(names = "--execution", description = "Execution ID (defaults to latest).")
    Long executionId;

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        return ClientCommands.runJobsLogs(seeds, jobId, executionId);
    }
}

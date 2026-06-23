package com.aegisos.cli.commands;

import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "status", description = "Show the status of a submitted job.")
public final class StatusCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Job id.")
    String jobId;

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        System.err.println("[DEPRECATED] Use: aegis jobs status " + jobId + "\n");
        JobsStatusCommand cmd = new JobsStatusCommand();
        cmd.jobId = this.jobId;
        cmd.seeds = this.seeds;
        return cmd.call();
    }
}

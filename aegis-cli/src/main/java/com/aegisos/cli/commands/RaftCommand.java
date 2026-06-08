package com.aegisos.cli.commands;

import picocli.CommandLine;

@CommandLine.Command(
        name = "raft",
        description = "Manage Raft cluster membership",
        subcommands = {
                AddVoterCommand.class,
                RemoveVoterCommand.class
        })
public class RaftCommand implements Runnable {
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}

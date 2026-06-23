package com.aegisos.cli.commands;

import picocli.CommandLine;

@CommandLine.Command(
        name = "admin",
        description = "Manage cluster administration tasks",
        subcommands = {
                AddMemberCommand.class,
                RemoveMemberCommand.class
        })
public class AdminCommand implements Runnable {
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}

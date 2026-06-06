package com.aegisos.cli.commands;

import picocli.CommandLine;

@CommandLine.Command(
        name = "allocator",
        description = "Manage and inspect resource allocator",
        subcommands = {
                AllocatorStatusCommand.class
        })
public class AllocatorCommand implements Runnable {
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}

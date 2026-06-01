package com.aegisos.cli.commands;

import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "ls", description = "List files stored in the cluster.")
public final class LsCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", arity = "0..1", description = "Path prefix (default /).")
    String path = "/";

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        return ClientCommands.runLs(seeds, path);
    }
}

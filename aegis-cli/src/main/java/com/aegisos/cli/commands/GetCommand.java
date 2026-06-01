package com.aegisos.cli.commands;

import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "get", description = "Download a file from the cluster file system.")
public final class GetCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Cluster path, e.g. /cluster/path.txt.")
    String remotePath;

    @CommandLine.Parameters(index = "1", description = "Local destination path.")
    String localFile;

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        return ClientCommands.runGet(seeds, remotePath, localFile);
    }
}

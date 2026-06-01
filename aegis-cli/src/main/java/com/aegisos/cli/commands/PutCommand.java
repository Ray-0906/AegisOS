package com.aegisos.cli.commands;

import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "put", description = "Upload a local file into the cluster file system.")
public final class PutCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Local file path.")
    String localFile;

    @CommandLine.Parameters(index = "1", description = "Cluster path, e.g. /cluster/path.txt.")
    String remotePath;

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        return ClientCommands.runPut(seeds, localFile, remotePath);
    }
}

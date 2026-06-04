package com.aegisos.cli.commands;

import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "list", description = "List uploaded artifacts.")
public final class ArtifactListCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--seed", description = "Seed peer(s) to connect to.")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        return ClientCommands.runArtifactList(seeds);
    }
}

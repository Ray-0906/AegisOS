package com.aegisos.cli.commands;

import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "upload", description = "Upload a JAR artifact to the cluster.")
public final class ArtifactUploadCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", description = "Path to the JAR file.")
    String jarPath;

    @CommandLine.Option(names = "--seed", description = "Seed peer(s) to connect to.")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        return ClientCommands.runArtifactUpload(seeds, jarPath);
    }
}

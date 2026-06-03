package com.aegisos.cli.commands;

import picocli.CommandLine;

@CommandLine.Command(name = "artifact",
        description = "Manage cluster artifacts.",
        subcommands = { ArtifactUploadCommand.class, ArtifactListCommand.class })
public final class ArtifactCommand implements Runnable {
    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }
}

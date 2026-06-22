package com.aegisos.cli.commands;

import com.aegisos.cli.util.RestCliHelper;
import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
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
        if (seeds.isEmpty()) {
            System.err.println("aegis get: at least one --seed is required");
            return 2;
        }

        try {
            AegisClient client = RestCliHelper.createClient(seeds);
            byte[] data = client.getFile(remotePath);
            Files.write(Path.of(localFile), data);
            System.out.println("Downloaded " + remotePath + " -> " + localFile + " (" + data.length + " bytes)");
            return 0;
        } catch (Exception e) {
            System.err.println("aegis get failed: " + e.getMessage());
            return 1;
        }
    }
}

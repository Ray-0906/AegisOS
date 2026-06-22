package com.aegisos.cli.commands;

import com.aegisos.cli.util.RestCliHelper;
import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
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
        if (seeds.isEmpty()) {
            System.err.println("aegis put: at least one --seed is required");
            return 2;
        }

        try {
            AegisClient client = RestCliHelper.createClient(seeds);
            byte[] data = Files.readAllBytes(Path.of(localFile));
            client.putFile(remotePath, data);
            System.out.println("Uploaded " + localFile + " -> " + remotePath + " (" + data.length + " bytes)");
            return 0;
        } catch (Exception e) {
            System.err.println("aegis put failed: " + e.getMessage());
            return 1;
        }
    }
}

package com.aegisos.cli.commands;

import com.aegisos.api.dto.file.ListFilesResponse;
import com.aegisos.cli.util.RestCliHelper;
import com.aegisos.client.AegisClient;
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
        if (seeds.isEmpty()) {
            System.err.println("aegis ls: at least one --seed is required");
            return 2;
        }

        try {
            AegisClient client = RestCliHelper.createClient(seeds);
            ListFilesResponse response = client.listFiles(path);

            System.out.printf("%-30s %12s %s%n", "NAME", "SIZE", "CHUNKS");
            for (ListFilesResponse.FileInfo f : response.files) {
                System.out.printf("%-30s %12d %d%n", f.name, f.size, f.chunks);
            }
            return 0;
        } catch (Exception e) {
            System.err.println("aegis ls failed: " + e.getMessage());
            return 1;
        }
    }
}

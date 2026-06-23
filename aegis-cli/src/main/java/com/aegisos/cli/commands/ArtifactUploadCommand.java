package com.aegisos.cli.commands;

import com.aegisos.api.dto.artifact.ArtifactUploadResponse;
import com.aegisos.cli.util.RestCliHelper;
import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
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
        if (seeds.isEmpty()) {
            System.err.println("aegis artifact upload: at least one --seed is required");
            return 2;
        }

        try {
            AegisClient client = RestCliHelper.createClient(seeds);
            Path p = Path.of(jarPath);
            byte[] data = Files.readAllBytes(p);
            String name = p.getFileName().toString();
            
            ArtifactUploadResponse response = client.uploadArtifact(name, data);
            System.out.println("Uploaded " + response.name + " (artifact: " + response.artifactId + ", size: " + response.sizeBytes + " bytes)");
            return 0;
        } catch (Exception e) {
            System.err.println("upload failed: " + e.getMessage());
            return 1;
        }
    }
}

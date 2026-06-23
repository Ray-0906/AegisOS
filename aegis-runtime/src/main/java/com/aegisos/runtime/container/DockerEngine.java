package com.aegisos.runtime.container;

import com.aegisos.proto.ContainerSpec;
import com.aegisos.proto.EnvVar;
import com.aegisos.proto.ResourceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

public class DockerEngine implements ContainerEngine {
    private static final Logger log = LoggerFactory.getLogger(DockerEngine.class);

    @Override
    public boolean available() {
        try {
            Process process = new ProcessBuilder("docker", "info").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String run(String name, ContainerSpec spec, ResourceRequest resources, Path workDir) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("-d"); // detach
        command.add("--name");
        command.add(name);

        // Map workspace
        command.add("-v");
        command.add(workDir.toAbsolutePath().toString() + ":/workspace");
        command.add("-w");
        command.add("/workspace");

        if (resources.getMemoryMb() > 0) {
            command.add("--memory");
            command.add(resources.getMemoryMb() + "m");
        }

        // Env vars
        for (EnvVar env : spec.getEnvList()) {
            command.add("-e");
            command.add(env.getKey() + "=" + env.getValue());
        }

        command.add(spec.getImage());
        command.addAll(spec.getCmdList());

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String error = new String(process.getErrorStream().readAllBytes());
            throw new RuntimeException("Failed to start docker container: " + error);
        }
        return name; // The container id/name is 'name'
    }

    @Override
    public void stop(String containerId) throws Exception {
        Process process = new ProcessBuilder("docker", "stop", "-t", "5", containerId).start();
        process.waitFor();
        new ProcessBuilder("docker", "rm", "-f", containerId).start().waitFor();
    }

    @Override
    public OptionalInt exitCode(String containerId) throws Exception {
        Process process = new ProcessBuilder("docker", "inspect", "--format={{.State.Status}},{{.State.ExitCode}}", containerId).start();
        process.waitFor();
        String output = new String(process.getInputStream().readAllBytes()).trim();
        if (output.isEmpty()) {
            return OptionalInt.empty();
        }
        String[] parts = output.split(",");
        if (parts.length == 2 && ("exited".equals(parts[0]) || "dead".equals(parts[0]))) {
            return OptionalInt.of(Integer.parseInt(parts[1]));
        }
        return OptionalInt.empty();
    }

    @Override
    public byte[] stdout(String containerId) throws Exception {
        return readLogs(containerId, false);
    }

    @Override
    public byte[] stderr(String containerId) throws Exception {
        return readLogs(containerId, true);
    }

    private byte[] readLogs(String containerId, boolean isStderr) throws IOException, InterruptedException {
        // Unfortunately docker logs interleaves stdout and stderr if not careful.
        // We will just return the whole log for now or we could use docker logs.
        // Or redirect to file in docker. We will do best effort.
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("logs");
        cmd.add(containerId);
        
        Process process = new ProcessBuilder(cmd).start();
        process.waitFor();
        return isStderr ? process.getErrorStream().readAllBytes() : process.getInputStream().readAllBytes();
    }
}

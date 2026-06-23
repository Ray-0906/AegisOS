package com.aegisos.cli.commands;

import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.client.AegisClient;
import picocli.CommandLine;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "process", description = "Process management commands.",
        subcommands = { ProcessCommand.Submit.class, ProcessCommand.ListCmd.class, ProcessCommand.Status.class, ProcessCommand.Cancel.class })
public final class ProcessCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    private static AegisClient createClient(List<String> seeds) {
        if (seeds == null || seeds.isEmpty()) {
            throw new IllegalArgumentException("At least one --seed is required");
        }
        return new AegisClient(seeds.stream().map(s -> URI.create("http://" + s)).toList());
    }

    @CommandLine.Command(name = "submit", description = "Submit a process.")
    public static final class Submit implements Callable<Integer> {
        @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
        List<String> seeds = List.of();
        
        @CommandLine.Option(names = "--artifact", required = true, description = "Artifact ID")
        String artifact;

        @CommandLine.Option(names = "--cpu", defaultValue = "1", description = "CPU cores")
        int cpu;

        @CommandLine.Option(names = "--memory", defaultValue = "512", description = "Memory MB")
        long memory;

        @CommandLine.Option(names = {"--command"}, description = "Custom execution command (use {artifact} as the file path placeholder)")
        private String executionCommand;

        @CommandLine.Option(names = {"--pipe-to"}, description = "Process ID to pipe output to")
        private String pipeToProcessId;

        @Override
        public Integer call() {
            try {
                AegisClient client = createClient(seeds);
                String processId = client.submitProcess(artifact, cpu, memory, executionCommand, pipeToProcessId);
                System.out.println(processId);
                return 0;
            } catch (Exception e) {
                System.err.println("Submit failed: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "list", description = "List processes.")
    public static final class ListCmd implements Callable<Integer> {
        @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
        List<String> seeds = List.of();

        @Override
        public Integer call() {
            try {
                AegisClient client = createClient(seeds);
                List<ProcessRecord> processes = client.listProcesses();
                System.out.printf("%-36s %-12s %s%n", "PROCESS ID", "STATE", "NODE");
                for (ProcessRecord p : processes) {
                    System.out.printf("%-36s %-12s %s%n", p.processId(), p.state(), p.ownerNodeId() == null ? "" : p.ownerNodeId());
                }
                return 0;
            } catch (Exception e) {
                System.err.println("List failed: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "status", description = "Get process status.")
    public static final class Status implements Callable<Integer> {
        @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
        List<String> seeds = List.of();

        @CommandLine.Parameters(index = "0", description = "Process ID")
        String processId;

        @Override
        public Integer call() {
            try {
                AegisClient client = createClient(seeds);
                ProcessRecord p = client.getProcess(processId);
                System.out.println("Process ID: " + p.processId());
                System.out.println("Artifact ID: " + p.artifactId());
                System.out.println("State: " + p.state());
                System.out.println("Node: " + (p.ownerNodeId() == null ? "None" : p.ownerNodeId()));
                System.out.println("Resources: " + p.resources().cpuCores() + " cores, " + p.resources().memoryMb() + " MB");
                return 0;
            } catch (Exception e) {
                System.err.println("Status failed: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "cancel", description = "Cancel a process.")
    public static final class Cancel implements Callable<Integer> {
        @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
        List<String> seeds = List.of();

        @CommandLine.Parameters(index = "0", description = "Process ID")
        String processId;

        @Override
        public Integer call() {
            try {
                AegisClient client = createClient(seeds);
                client.cancelProcess(processId);
                System.out.println("Success");
                return 0;
            } catch (Exception e) {
                System.err.println("Cancel failed: " + e.getMessage());
                return 1;
            }
        }
    }
}

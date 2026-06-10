package com.aegisos.cli;

import com.aegisos.cli.commands.ArtifactCommand;
import com.aegisos.cli.commands.GetCommand;
import com.aegisos.cli.commands.InfoCommand;
import com.aegisos.cli.commands.LsCommand;
import com.aegisos.cli.commands.NodesCommand;
import com.aegisos.cli.commands.PutCommand;
import com.aegisos.cli.commands.RunCommand;
import com.aegisos.cli.commands.StartCommand;
import com.aegisos.cli.commands.StatusCommand;
import com.aegisos.cli.commands.TestCommand;
import com.aegisos.cli.commands.AllocatorCommand;
import com.aegisos.cli.commands.RaftCommand;
import com.aegisos.cli.commands.JobsCommand;
import picocli.CommandLine;

@CommandLine.Command(
        name = "aegis",
        mixinStandardHelpOptions = true,
        version = "AegisOS 0.1.0",
        description = "AegisOS - secure distributed OS runtime over a private P2P network.",
        subcommands = {
                StartCommand.class,
                InfoCommand.class,
                NodesCommand.class,
                PutCommand.class,
                GetCommand.class,
                LsCommand.class,
                RunCommand.class,
                StatusCommand.class,
                ArtifactCommand.class,
                TestCommand.class,
                AllocatorCommand.class,
                RaftCommand.class,
                JobsCommand.class
        })
// NOTE: all subcommands are wired now; their implementations are completed across phases.
public final class AegisCLI implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        int exit = new CommandLine(new AegisCLI()).execute(args);
        System.exit(exit);
    }
}

package com.aegisos.cli.commands;

import com.aegisos.core.model.Endpoint;
import com.aegisos.node.AegisNode;
import com.aegisos.node.NodeConfig;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "start", description = "Start an AegisOS node in this process.")
public final class StartCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--port", description = "P2P listen port (default 9000).")
    int port = 9000;

    @CommandLine.Option(names = "--advertise", description = "Advertised host (default 127.0.0.1).")
    String advertise = "127.0.0.1";

    @CommandLine.Option(names = "--home", description = "Node home directory (default ~/.aegis).")
    Path home;

    @CommandLine.Option(names = "--seed", description = "Seed peer ip:port (repeatable).")
    List<String> seeds = List.of();

    @CommandLine.Option(names = "--metrics-port",
            description = "HTTP metrics port (default: P2P port + 10000, 0 = disabled).")
    int metricsPort = -1;  // -1 signals "use default"

    @CommandLine.Option(names = "--bootstrap", description = "Bootstrap a brand new cluster.")
    boolean bootstrap = false;

    @Override
    public Integer call() throws Exception {
        int resolvedMetricsPort = (metricsPort == -1) ? (port + 10000) : metricsPort;
        NodeConfig config = new NodeConfig()
                .port(port)
                .advertiseHost(advertise)
                .apiPort(resolvedMetricsPort)
                .bootstrap(bootstrap);
        if (home != null) {
            config.homeDir(home);
        }
        config.loadSeedsFile();
        for (String s : seeds) {
            config.addSeed(Endpoint.parse(s));
        }

        AegisNode node = new AegisNode(config);
        Runtime.getRuntime().addShutdownHook(new Thread(node::close));
        node.start();
        String metricsUrl = resolvedMetricsPort > 0
                ? "  metrics: http://127.0.0.1:" + resolvedMetricsPort + "/metrics"
                : "  metrics: disabled";
        System.out.println("Node " + node.identity().nodeId().shortId()
                + " started on port " + node.network().boundPort() + ". Ctrl-C to stop.\n"
                + metricsUrl);
        Thread.currentThread().join();
        return 0;
    }
}

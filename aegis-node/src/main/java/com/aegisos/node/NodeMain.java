package com.aegisos.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone entry point for running a node directly (the CLI {@code aegis start}
 * command delegates here as well).
 */
public final class NodeMain {

    private static final Logger log = LoggerFactory.getLogger(NodeMain.class);

    private NodeMain() {
    }

    public static void main(String[] args) throws Exception {
        NodeConfig config = new NodeConfig();
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--port" -> config.port(Integer.parseInt(args[++i]));
                case "--advertise" -> config.advertiseHost(args[++i]);
                default -> { /* ignore unknown */ }
            }
        }
        config.loadSeedsFile();

        AegisNode node = new AegisNode(config);
        Runtime.getRuntime().addShutdownHook(new Thread(node::close));
        node.start();
        log.info("Node running. Press Ctrl-C to stop.");
        Thread.currentThread().join();
    }
}

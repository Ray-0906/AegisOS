package com.aegisos.network.tcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * Accepts inbound TCP connections and hands each socket to a consumer on its own
 * virtual thread. Crypto/handshake handling lives in the consumer.
 */
public final class TcpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TcpServer.class);

    private final int port;
    private final Consumer<Socket> socketConsumer;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    public TcpServer(int port, Consumer<Socket> socketConsumer) {
        this.port = port;
        this.socketConsumer = socketConsumer;
    }

    public int boundPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        // Platform daemon thread: the accept loop must keep running even when the
        // virtual thread scheduler is starved (pinned carrier threads elsewhere).
        acceptThread = Thread.ofPlatform().daemon().name("aegis-accept-" + boundPort()).start(this::acceptLoop);
        log.info("TCP server listening on port {}", boundPort());
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                // Platform daemon thread: handshake handling must not depend on the
                // virtual thread scheduler, or inbound peers see "Read timed out".
                Thread.ofPlatform().daemon().name("aegis-conn").start(() -> socketConsumer.accept(socket));
            } catch (IOException e) {
                if (running) {
                    log.warn("Accept failed: {}", e.getMessage());
                }
            }
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }
}

package com.aegisos.network;

import com.aegisos.core.identity.IdentityService;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.message.AegisMessage;
import com.aegisos.core.message.MessageType;
import com.aegisos.core.model.Endpoint;
import com.aegisos.network.crypto.EstablishedSession;
import com.aegisos.network.crypto.HandshakeHandler;
import com.aegisos.network.tcp.TcpConnectionPool;
import com.aegisos.network.tcp.TcpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Top-level secure transport. Provides authenticated, encrypted, message-oriented
 * communication between nodes, plus a request/response RPC abstraction with correlation.
 *
 * <p>Every cross-node call in AegisOS goes through this layer; no upper layer touches a
 * raw socket.
 */
public final class NetworkLayer implements PeerConnection.InboundHandler, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NetworkLayer.class);
    private static final long DEFAULT_RPC_TIMEOUT_MS = 5_000;
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private final IdentityService identity;
    private final int port;
    private final String advertiseHost;
    private volatile String advertisedAddress;
    private final HandshakeHandler handshakeHandler;
    private final TcpConnectionPool pool = new TcpConnectionPool();
    private final Map<MessageType, MessageHandler> handlers = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<AegisMessage>> pending = new ConcurrentHashMap<>();
    private final AtomicLong correlationCounter = new AtomicLong(1);

    private volatile Function<NodeId, Optional<Endpoint>> addressResolver = id -> Optional.empty();
    private TcpServer server;

    public NetworkLayer(IdentityService identity, int port, String advertisedHost) {
        this.identity = identity;
        this.port = port;
        this.advertiseHost = advertisedHost;
        this.advertisedAddress = advertisedHost + ":" + port;
        this.handshakeHandler = new HandshakeHandler(identity, () -> advertisedAddress);
    }

    public void setAddressResolver(Function<NodeId, Optional<Endpoint>> resolver) {
        this.addressResolver = resolver;
    }

    public void registerHandler(MessageType type, MessageHandler handler) {
        handlers.put(type, handler);
    }

    public NodeId localNodeId() {
        return identity.nodeId();
    }

    public int boundPort() {
        return server != null ? server.boundPort() : port;
    }

    public TcpConnectionPool pool() {
        return pool;
    }

    public void start() throws IOException {
        server = new TcpServer(port, this::acceptSocket);
        server.start();
        // Now that the ephemeral/real port is known, advertise the correct address.
        this.advertisedAddress = advertiseHost + ":" + server.boundPort();
        log.info("NetworkLayer started, node {} advertising {}", localNodeId().shortId(), advertisedAddress);
    }

    private void acceptSocket(Socket socket) {
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            EstablishedSession session = handshakeHandler.respond(in, out);
            registerConnection(socket, in, out, session);
        } catch (IOException e) {
            log.debug("Inbound handshake failed: {}", e.getMessage());
            closeQuietly(socket);
        }
    }

    /** Dials a peer by endpoint, performs the handshake, and returns its verified node id. */
    public NodeId connect(Endpoint endpoint) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(endpoint.host(), endpoint.port()), CONNECT_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        EstablishedSession session = handshakeHandler.initiate(in, out);
        PeerConnection conn = registerConnection(socket, in, out, session);
        return conn.remoteNodeId();
    }

    private PeerConnection registerConnection(Socket socket, DataInputStream in,
                                              DataOutputStream out, EstablishedSession session) {
        PeerConnection conn = new PeerConnection(socket, in, out, session, identity, this);
        PeerConnection winner = pool.putIfAbsent(conn);
        if (winner != conn) {
            log.debug("Duplicate connection to {} closed", conn.remoteNodeId().shortId());
            conn.close();
            return winner;
        }
        conn.startReceiving();
        return conn;
    }

    public boolean isConnected(NodeId nodeId) {
        return pool.get(nodeId).isPresent();
    }

    private Optional<PeerConnection> ensureConnected(NodeId nodeId) {
        Optional<PeerConnection> existing = pool.get(nodeId);
        if (existing.isPresent()) {
            return existing;
        }
        Optional<Endpoint> endpoint = addressResolver.apply(nodeId);
        if (endpoint.isEmpty()) {
            return Optional.empty();
        }
        try {
            connect(endpoint.get());
            return pool.get(nodeId);
        } catch (IOException e) {
            log.debug("Could not connect to {}: {}", nodeId.shortId(), e.getMessage());
            return Optional.empty();
        }
    }

    /** Fire-and-forget send. Returns false if the peer is unreachable. */
    public boolean sendAsync(NodeId nodeId, MessageType type, byte[] payload) {
        Optional<PeerConnection> conn = ensureConnected(nodeId);
        if (conn.isEmpty()) {
            return false;
        }
        try {
            conn.get().send(type, payload, 0L);
            return true;
        } catch (IOException e) {
            log.debug("Send to {} failed: {}", nodeId.shortId(), e.getMessage());
            pool.remove(nodeId);
            return false;
        }
    }

    /** Request/response RPC with default timeout. */
    public CompletableFuture<AegisMessage> request(NodeId nodeId, MessageType type, byte[] payload) {
        return request(nodeId, type, payload, DEFAULT_RPC_TIMEOUT_MS);
    }

    public CompletableFuture<AegisMessage> request(NodeId nodeId, MessageType type,
                                                   byte[] payload, long timeoutMs) {
        Optional<PeerConnection> conn = ensureConnected(nodeId);
        if (conn.isEmpty()) {
            return CompletableFuture.failedFuture(new IOException("not connected to " + nodeId.shortId()));
        }
        long correlation = correlationCounter.getAndIncrement();
        CompletableFuture<AegisMessage> future = new CompletableFuture<>();
        pending.put(correlation, future);
        try {
            conn.get().send(type, payload, correlation);
        } catch (IOException e) {
            pending.remove(correlation);
            return CompletableFuture.failedFuture(e);
        }
        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((r, t) -> pending.remove(correlation));
    }

    @Override
    public void onMessage(PeerConnection connection, AegisMessage message, long correlation) {
        // A reply to one of our outstanding requests?
        if (correlation != 0) {
            CompletableFuture<AegisMessage> waiting = pending.remove(correlation);
            if (waiting != null) {
                waiting.complete(message);
                return;
            }
        }
        MessageHandler handler = handlers.get(message.type());
        if (handler == null) {
            log.debug("No handler for {} from {}", message.type(), message.sender().shortId());
            return;
        }
        try {
            AegisMessage reply = handler.handle(message);
            if (reply != null) {
                connection.send(reply.type(), reply.payload(), correlation);
            }
        } catch (Exception e) {
            log.warn("Handler for {} threw: {}", message.type(), e.toString());
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.close();
        }
        pool.closeAll();
        pending.values().forEach(f -> f.completeExceptionally(new IOException("network closed")));
        pending.clear();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}

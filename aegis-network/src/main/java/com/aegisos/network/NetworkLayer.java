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
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static class PendingRequest {
        final NodeId target;
        final CompletableFuture<AegisMessage> future;
        
        PendingRequest(NodeId target, CompletableFuture<AegisMessage> future) {
            this.target = target;
            this.future = future;
        }
    }

    private final Map<Long, PendingRequest> pending = new ConcurrentHashMap<>();
    private final AtomicLong correlationCounter = new AtomicLong(1);

    public interface MessageFilter {
        boolean test(NodeId from, NodeId to, MessageType type, byte[] payload);
    }

    private static volatile MessageFilter messageFilter = (from, to, type, payload) -> true;

    public static void setMessageFilter(MessageFilter filter) {
        messageFilter = filter != null ? filter : (from, to, type, payload) -> true;
    }

    public static void clearMessageFilter() {
        messageFilter = (from, to, type, payload) -> true;
    }

    /**
     * Message types that MUST be processed inline on the PeerConnection.receiveLoop() thread.
     *
     * <p>These are the latency-sensitive Raft peer messages. Their handlers hold the Raft
     * mutex briefly (microseconds) and immediately return a reply — they never block on
     * consensus, I/O, or any external service. Dispatching them to an executor would add
     * scheduling overhead and, more importantly, break ordering: two concurrent AppendEntries
     * arriving on the same connection could race through the Raft lock out of order.
     *
     * <p>Rule: a message type belongs here if and only if its handler is:
     *   1. Non-blocking (no Future.get(), no I/O wait, no external RPC), AND
     *   2. Order-sensitive (the protocol assumes messages from the same peer are serialised).
     */
    private static final Set<MessageType> FAST_PATH_TYPES = EnumSet.of(
            MessageType.APPEND_ENTRIES,
            MessageType.APPEND_ENTRIES_RESULT,
            MessageType.REQUEST_VOTE,
            MessageType.REQUEST_VOTE_RESULT,
            MessageType.PING,
            MessageType.PONG
    );

    /**
     * Off-socket dispatcher for slow-path messages.
     *
     * <p>Any handler that may block (e.g. CLIENT_COMMAND → raftNode.submit().get(),
     * STORE_CHUNK writing to disk, RUN_JOB spawning a job) is submitted here so the
     * receiveLoop thread is never stalled. Virtual threads are ideal: they are cheap,
     * block gracefully on I/O, and impose no artificial parallelism limit.
     */
    private final ExecutorService handlerExecutor =
            com.aegisos.core.ExecutorRegistry.register("networkLayer", Executors.newVirtualThreadPerTaskExecutor());

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
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            EstablishedSession session = handshakeHandler.respond(in, out);
            socket.setSoTimeout(0);
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
        socket.setSoTimeout(CONNECT_TIMEOUT_MS);
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        EstablishedSession session = handshakeHandler.initiate(in, out);
        socket.setSoTimeout(0);
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
        if (!messageFilter.test(localNodeId(), nodeId, type, payload)) {
            return false;
        }
        Optional<PeerConnection> conn = ensureConnected(nodeId);
        if (conn.isEmpty()) {
            return false;
        }
        try {
            conn.get().send(type, payload, 0L, false);
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
        if (!messageFilter.test(localNodeId(), nodeId, type, payload)) {
            return CompletableFuture.failedFuture(new IOException("partitioned from " + nodeId.shortId()));
        }
        Optional<PeerConnection> conn = ensureConnected(nodeId);
        if (conn.isEmpty()) {
            return CompletableFuture.failedFuture(new IOException("not connected to " + nodeId.shortId()));
        }
        long correlation = correlationCounter.getAndIncrement();
        CompletableFuture<AegisMessage> future = new CompletableFuture<>();
        pending.put(correlation, new PendingRequest(nodeId, future));
        try {
            conn.get().send(type, payload, correlation, false);
        } catch (IOException e) {
            pool.remove(nodeId);
            pending.remove(correlation);
            return CompletableFuture.failedFuture(e);
        }
        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .whenComplete((r, t) -> pending.remove(correlation));
    }

    @Override
    public void onMessage(PeerConnection connection, AegisMessage message, long correlation, boolean isResponse) {
        if (!messageFilter.test(message.sender(), localNodeId(), message.type(), message.payload())) {
            log.debug("Partition dropped inbound message from {} to {}", message.sender().shortId(), localNodeId().shortId());
            return;
        }
        // ----------------------------------------------------------------
        // Tier 0: correlation replies (e.g. AppendEntriesResult sent back to
        // the LogReplicator that made a request() call).
        // Complete the waiting future immediately on the receive thread.
        // This is always safe: future.complete() is non-blocking.
        // ----------------------------------------------------------------
        if (correlation != 0 && isResponse) {
            PendingRequest req = pending.remove(correlation);
            if (req != null) {
                req.future.complete(message);
                return;
            }
        }

        MessageHandler handler = handlers.get(message.type());
        if (handler == null) {
            log.debug("No handler for {} from {}", message.type(), message.sender().shortId());
            return;
        }

        if (FAST_PATH_TYPES.contains(message.type())) {
            // ----------------------------------------------------------------
            // Tier 1: Fast-path — execute inline on the receive thread.
            // Handlers here are lock-only (Raft mutex), non-blocking, and
            // order-sensitive. Keeping them on the socket thread preserves
            // the per-connection serialisation that Raft depends on.
            // ----------------------------------------------------------------
            try {
                AegisMessage response = handler.handle(message);
                if (response != null && correlation != 0) {
                    connection.send(response.type(), response.payload(), correlation, true);
                }
            } catch (Exception e) {
                log.warn("Handler for {} threw: {}", message.type(), e.toString());
            }
        } else {
            // ----------------------------------------------------------------
            // Tier 2: Slow-path — dispatch to virtual-thread executor.
            // Any handler that may block (Raft propose, disk I/O, downstream
            // RPC) must not run on the receive thread or it will deadlock
            // Raft replication (the root cause of the 25-second stall bug).
            // ----------------------------------------------------------------
            final long correlationId = correlation;
            handlerExecutor.submit(() -> {
                long start = System.nanoTime();
                try {
                    AegisMessage reply = handler.handle(message);
                    long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
                    if (elapsedMs > 100) {
                        log.warn("SLOW handler: {} from {} took {}ms",
                                message.type(), message.sender().shortId(), elapsedMs);
                    }
                    if (reply != null) {
                        connection.send(reply.type(), reply.payload(), correlationId, true);
                    }
                } catch (Exception e) {
                    log.warn("Handler for {} threw: {}", message.type(), e.toString());
                }
            });
        }
    }

    @Override
    public void onConnectionClosed(PeerConnection connection) {
        NodeId remote = connection.remoteNodeId();
        if (pool.isWinner(connection)) {
            pool.remove(remote);
        }
        
        java.util.List<Long> toFail = new java.util.ArrayList<>();
        pending.forEach((corr, req) -> {
            if (req.target.equals(remote)) {
                toFail.add(corr);
            }
        });
        
        for (Long corr : toFail) {
            PendingRequest req = pending.remove(corr);
            if (req != null) {
                req.future.completeExceptionally(new IOException("Connection to " + remote.shortId() + " closed"));
            }
        }
    }

    @Override
    public void close() {
        handlerExecutor.shutdownNow();
        try {
            boolean terminated = handlerExecutor.awaitTermination(3, TimeUnit.SECONDS);
            // #region agent log
            java.util.Map<String, Object> data = new java.util.HashMap<>();
            data.put("terminated", terminated);
            data.put("isShutdown", handlerExecutor.isShutdown());
            data.put("isTerminated", handlerExecutor.isTerminated());
            com.aegisos.core.util.DebugLogger.log("NetworkLayer.java:319", "handlerExecutor shutdown status",
                data, "D", "pre-fix");
            // #endregion
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (server != null) {
            server.close();
        }
        pool.closeAll();
        pending.values().forEach(req -> req.future.completeExceptionally(new IOException("network closed")));
        pending.clear();
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}

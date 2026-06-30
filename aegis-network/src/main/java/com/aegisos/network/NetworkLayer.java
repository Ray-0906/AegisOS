package com.aegisos.network;

import com.aegisos.core.identity.IdentityService;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.message.AegisMessage;
import com.aegisos.core.message.MessageType;
import com.aegisos.core.model.Endpoint;
import com.aegisos.core.security.IdentityManager;
import com.aegisos.network.crypto.EstablishedSession;
import com.aegisos.network.crypto.HandshakeHandler;
import com.aegisos.network.tcp.TcpConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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

public final class NetworkLayer implements PeerConnection.InboundHandler, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NetworkLayer.class);
    private static final long DEFAULT_RPC_TIMEOUT_MS = 5_000;
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private final IdentityService identity;
    private final IdentityManager identityManager;
    private final int port;
    private final String advertiseHost;
    private volatile String advertisedAddress;
    private final HandshakeHandler handshakeHandler;
    private final TcpConnectionPool pool = new TcpConnectionPool();
    private final Map<MessageType, MessageHandler> handlers = new ConcurrentHashMap<>();
    
    private SSLServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

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
    
    private final Map<String, VirtualInputStream> activeStreams = new ConcurrentHashMap<>();

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

    private static final Set<MessageType> FAST_PATH_TYPES = EnumSet.of(
            MessageType.APPEND_ENTRIES,
            MessageType.APPEND_ENTRIES_RESULT,
            MessageType.REQUEST_VOTE,
            MessageType.REQUEST_VOTE_RESULT,
            MessageType.PING,
            MessageType.PONG
    );

    private final ExecutorService handlerExecutor =
            com.aegisos.core.ExecutorRegistry.register("networkLayer", Executors.newVirtualThreadPerTaskExecutor());

    private volatile Function<NodeId, Optional<Endpoint>> addressResolver = id -> Optional.empty();

    public NetworkLayer(IdentityService identity, IdentityManager identityManager, int port, String advertisedHost) {
        this.identity = identity;
        this.identityManager = identityManager;
        this.port = port;
        this.advertiseHost = advertisedHost;
        this.advertisedAddress = advertisedHost + ":" + port;
        this.handshakeHandler = new HandshakeHandler(identity, () -> advertisedAddress);
        
        registerHandler(MessageType.IPC_DATA, message -> {
            handleIpcData(message.sender(), message.payload());
            return null;
        });
        
        registerHandler(MessageType.IPC_EOF, message -> {
            handleIpcEof(message.sender(), message.payload());
            return null;
        });
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
        return serverSocket != null ? serverSocket.getLocalPort() : port;
    }

    public TcpConnectionPool pool() {
        return pool;
    }

    public void start() throws IOException {
        try {
            serverSocket = (SSLServerSocket) identityManager.getServerContext().getServerSocketFactory().createServerSocket(port);
            serverSocket.setNeedClientAuth(true);
            serverSocket.setReuseAddress(true);
            
            running = true;
            acceptThread = Thread.ofPlatform().daemon().name("aegis-accept-" + boundPort()).start(this::acceptLoop);
            
            this.advertisedAddress = advertiseHost + ":" + boundPort();
            log.debug("NetworkLayer started, node {} advertising {}", localNodeId().shortId(), advertisedAddress);
        } catch (Exception e) {
            throw new IOException("Failed to start secure server", e);
        }
    }
    
    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Thread.ofPlatform().daemon().name("aegis-conn").start(() -> acceptSocket(socket));
            } catch (IOException e) {
                if (running) {
                    log.warn("Accept failed: {}", e.getMessage());
                }
            }
        }
    }

    private void acceptSocket(Socket rawSocket) {
        SSLSocket socket = (SSLSocket) rawSocket;
        try {
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            socket.startHandshake();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            EstablishedSession session = handshakeHandler.respond(in, out);
            socket.setSoTimeout(0);
            registerConnection(socket, in, out, session);
        } catch (IOException e) {
            log.warn("Inbound handshake rejected: {}", e.getMessage());
            closeQuietly(socket);
        }
    }

    public NodeId connect(Endpoint endpoint) throws IOException {
        try {
            SSLSocket socket = (SSLSocket) identityManager.getClientContext().getSocketFactory().createSocket(endpoint.host(), endpoint.port());
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            
            try {
                socket.startHandshake();
            } catch (IOException e) {
                log.warn("Outbound handshake rejected by {}: {}", endpoint, e.getMessage());
                closeQuietly(socket);
                throw e;
            }
            
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            EstablishedSession session = handshakeHandler.initiate(in, out);
            socket.setSoTimeout(0);
            PeerConnection conn = registerConnection(socket, in, out, session);
            return conn.remoteNodeId();
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Failed to establish secure client connection", e);
        }
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

    public void sendIpcData(String targetNodeId, String targetProcessId, byte[] data) {
        try {
            NodeId target = NodeId.fromHex(targetNodeId);
            com.aegisos.proto.IpcChunkProto proto = com.aegisos.proto.IpcChunkProto.newBuilder()
                    .setProcessId(targetProcessId)
                    .setData(com.google.protobuf.ByteString.copyFrom(data))
                    .build();
            sendAsync(target, MessageType.IPC_DATA, proto.toByteArray());
        } catch (Exception e) {
            log.error("Failed to send IPC data to {}/{}", targetNodeId, targetProcessId, e);
        }
    }

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

    public VirtualInputStream registerIpcStream(String processId) {
        VirtualInputStream stream = new VirtualInputStream();
        activeStreams.put(processId, stream);
        return stream;
    }

    public void unregisterIpcStream(String processId) {
        VirtualInputStream stream = activeStreams.remove(processId);
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    private void handleIpcData(NodeId from, byte[] payload) {
        try {
            com.aegisos.proto.IpcChunkProto chunk = com.aegisos.proto.IpcChunkProto.parseFrom(payload);
            VirtualInputStream stream = activeStreams.get(chunk.getProcessId());
            if (stream != null) {
                stream.enqueueChunk(chunk.getData().toByteArray());
            }
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            log.error("Failed to parse IPC chunk from {}", from.shortId(), e);
        }
    }

    private void handleIpcEof(NodeId from, byte[] payload) {
        try {
            com.aegisos.proto.IpcChunkProto chunk = com.aegisos.proto.IpcChunkProto.parseFrom(payload);
            VirtualInputStream stream = activeStreams.get(chunk.getProcessId());
            if (stream != null) {
                stream.enqueueChunk(new byte[0]);
            }
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            log.error("Failed to parse IPC EOF from {}", from.shortId(), e);
        }
    }

    @Override
    public void onMessage(PeerConnection connection, AegisMessage message, long correlation, boolean isResponse) {
        if (!messageFilter.test(message.sender(), localNodeId(), message.type(), message.payload())) {
            log.debug("Partition dropped inbound message from {} to {}", message.sender().shortId(), localNodeId().shortId());
            return;
        }
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
            try {
                AegisMessage response = handler.handle(message);
                if (response != null && correlation != 0) {
                    connection.send(response.type(), response.payload(), correlation, true);
                }
            } catch (Exception e) {
                log.warn("Handler for {} threw: {}", message.type(), e.toString());
            }
        } else {
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
        running = false;
        handlerExecutor.shutdownNow();
        try {
            boolean terminated = handlerExecutor.awaitTermination(3, TimeUnit.SECONDS);
            log.trace("handlerExecutor shutdown status: terminated={} isShutdown={} isTerminated={}",
                    terminated, handlerExecutor.isShutdown(), handlerExecutor.isTerminated());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
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

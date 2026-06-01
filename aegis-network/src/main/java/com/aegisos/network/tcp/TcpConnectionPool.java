package com.aegisos.network.tcp;

import com.aegisos.core.identity.NodeId;
import com.aegisos.network.PeerConnection;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks live {@link PeerConnection}s keyed by peer {@link NodeId}. */
public final class TcpConnectionPool {

    private final ConcurrentHashMap<NodeId, PeerConnection> connections = new ConcurrentHashMap<>();

    /**
     * Registers a connection. If one to the same peer already exists, the existing one
     * is kept and the new connection is returned for the caller to close.
     *
     * @return the connection that should be used (existing if there was a race)
     */
    public PeerConnection putIfAbsent(PeerConnection connection) {
        PeerConnection existing = connections.putIfAbsent(connection.remoteNodeId(), connection);
        return existing != null ? existing : connection;
    }

    public boolean isWinner(PeerConnection connection) {
        return connections.get(connection.remoteNodeId()) == connection;
    }

    public Optional<PeerConnection> get(NodeId id) {
        return Optional.ofNullable(connections.get(id));
    }

    public void remove(NodeId id) {
        connections.remove(id);
    }

    public Collection<PeerConnection> all() {
        return connections.values();
    }

    public int size() {
        return connections.size();
    }

    public void closeAll() {
        connections.values().forEach(PeerConnection::close);
        connections.clear();
    }
}

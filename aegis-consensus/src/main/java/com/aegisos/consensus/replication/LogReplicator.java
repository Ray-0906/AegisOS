package com.aegisos.consensus.replication;

import com.aegisos.core.identity.NodeId;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-follower replication progress for the leader: {@code nextIndex} (the next
 * log index to send) and {@code matchIndex} (the highest index known replicated).
 */
public final class LogReplicator {

    private final ConcurrentHashMap<NodeId, Long> nextIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<NodeId, Long> matchIndex = new ConcurrentHashMap<>();

    public void initLeader(List<NodeId> peers, long nextIdx) {
        nextIndex.clear();
        matchIndex.clear();
        for (NodeId peer : peers) {
            nextIndex.put(peer, nextIdx);
            matchIndex.put(peer, 0L);
        }
    }

    public void ensurePeers(List<NodeId> peers, long defaultNext) {
        for (NodeId peer : peers) {
            nextIndex.putIfAbsent(peer, defaultNext);
            matchIndex.putIfAbsent(peer, 0L);
        }
    }

    public long nextIndex(NodeId peer) {
        return nextIndex.getOrDefault(peer, 1L);
    }

    public long matchIndex(NodeId peer) {
        return matchIndex.getOrDefault(peer, 0L);
    }

    public void onSuccess(NodeId peer, long newMatchIndex) {
        matchIndex.merge(peer, newMatchIndex, Math::max);
        nextIndex.put(peer, Math.max(nextIndex(peer), newMatchIndex + 1));
    }

    public void onFailure(NodeId peer) {
        nextIndex.computeIfPresent(peer, (k, v) -> Math.max(1L, v - 1));
    }

    public void onFailure(NodeId peer, long followerLastIndex) {
        nextIndex.computeIfPresent(peer, (k, v) -> Math.max(1L, Math.min(v - 1, followerLastIndex + 1)));
    }
}

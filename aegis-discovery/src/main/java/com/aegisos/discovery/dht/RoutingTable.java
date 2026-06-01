package com.aegisos.discovery.dht;

import com.aegisos.core.identity.NodeId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kademlia-style routing table over 256-bit node ids using the XOR distance metric.
 *
 * <p>Nodes are bucketed by the position of the most-significant differing bit relative
 * to the local id (k-buckets). Lookups return the closest nodes to a target by full XOR
 * distance, giving O(log N) routing in large clusters and exact closeness in small ones.
 */
public final class RoutingTable {

    public static final int ID_BITS = 256;
    public static final int K = 20; // bucket capacity

    private final NodeId self;
    private final List<Set<NodeId>> buckets;

    public RoutingTable(NodeId self) {
        this.self = self;
        this.buckets = new ArrayList<>(ID_BITS);
        for (int i = 0; i < ID_BITS; i++) {
            buckets.add(ConcurrentHashMap.newKeySet());
        }
    }

    public void add(NodeId node) {
        if (node.equals(self)) {
            return;
        }
        int idx = bucketIndex(node);
        if (idx < 0) {
            return;
        }
        Set<NodeId> bucket = buckets.get(idx);
        if (bucket.size() < K || bucket.contains(node)) {
            bucket.add(node);
        }
        // Eviction of stale entries (liveness-based) is a future refinement.
    }

    public void remove(NodeId node) {
        int idx = bucketIndex(node);
        if (idx >= 0) {
            buckets.get(idx).remove(node);
        }
    }

    /** Returns up to {@code count} known nodes closest to {@code target} by XOR distance. */
    public List<NodeId> closest(NodeId target, int count) {
        List<NodeId> all = new ArrayList<>();
        for (Set<NodeId> bucket : buckets) {
            all.addAll(bucket);
        }
        all.sort(Comparator.comparing(n -> n.xorDistance(target), RoutingTable::compareUnsigned));
        return all.size() > count ? all.subList(0, count) : all;
    }

    private int bucketIndex(NodeId other) {
        byte[] d = self.xorDistance(other);
        for (int i = 0; i < d.length; i++) {
            int b = d[i] & 0xFF;
            if (b != 0) {
                int leadingZerosInByte = Integer.numberOfLeadingZeros(b) - 24;
                int msbPos = i * 8 + leadingZerosInByte; // 0 = most significant bit
                return ID_BITS - 1 - msbPos;
            }
        }
        return -1; // identical id
    }

    static int compareUnsigned(byte[] a, byte[] b) {
        for (int i = 0; i < a.length; i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return 0;
    }
}

package com.aegisos.core.identity;

import com.aegisos.core.util.HexUtil;

import java.util.Arrays;

/**
 * A stable node identifier: {@code SHA-256(ed25519 public key)} (32 bytes).
 * Value type with content-based equality, suitable as a map key.
 */
public final class NodeId {

    public static final int LENGTH = 32;

    private final byte[] bytes;
    private final int hash;

    private NodeId(byte[] bytes) {
        if (bytes == null || bytes.length != LENGTH) {
            throw new IllegalArgumentException("NodeId must be " + LENGTH + " bytes");
        }
        this.bytes = bytes.clone();
        this.hash = Arrays.hashCode(this.bytes);
    }

    public static NodeId of(byte[] bytes) {
        return new NodeId(bytes);
    }

    public static NodeId fromHex(String hex) {
        return new NodeId(HexUtil.decode(hex));
    }

    public byte[] toBytes() {
        return bytes.clone();
    }

    public String toHex() {
        return HexUtil.encode(bytes);
    }

    public String shortId() {
        return HexUtil.shortId(bytes);
    }

    /** XOR distance metric used by the Kademlia routing table. */
    public byte[] xorDistance(NodeId other) {
        byte[] d = new byte[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            d[i] = (byte) (this.bytes[i] ^ other.bytes[i]);
        }
        return d;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof NodeId other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "NodeId(" + shortId() + ")";
    }
}

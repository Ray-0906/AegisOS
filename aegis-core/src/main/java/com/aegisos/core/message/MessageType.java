package com.aegisos.core.message;

import java.util.HashMap;
import java.util.Map;

/**
 * Logical message types carried in the {@code message_type} field of the wire header.
 * Stable integer codes so the protocol can evolve without breaking older peers.
 */
public enum MessageType {
    HELLO(1),
    VERIFY(2),
    PING(3),
    PONG(4),

    GOSSIP_SYN(10),
    GOSSIP_ACK(11),
    RESOURCES(12),
    FIND_NODE(13),
    FIND_NODE_RESULT(14),

    REQUEST_VOTE(20),
    REQUEST_VOTE_RESULT(21),
    APPEND_ENTRIES(22),
    APPEND_ENTRIES_RESULT(23),
    CLIENT_COMMAND(24),
    CLIENT_COMMAND_RESULT(25),

    STORE_CHUNK(30),
    STORE_CHUNK_ACK(31),
    FETCH_CHUNK(32),
    FETCH_CHUNK_RESULT(33),

    SUBMIT_JOB(40),
    PROBE(41),
    PROBE_RESULT(42),
    RUN_JOB(43),
    JOB_UPDATE(44),
    JOB_DONE(45);

    private final int code;

    MessageType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    private static final Map<Integer, MessageType> BY_CODE = new HashMap<>();

    static {
        for (MessageType t : values()) {
            BY_CODE.put(t.code, t);
        }
    }

    public static MessageType fromCode(int code) {
        MessageType t = BY_CODE.get(code);
        if (t == null) {
            throw new IllegalArgumentException("unknown message type code: " + code);
        }
        return t;
    }
}

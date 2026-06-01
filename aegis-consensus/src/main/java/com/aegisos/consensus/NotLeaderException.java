package com.aegisos.consensus;

import com.aegisos.core.identity.NodeId;

/** Thrown when a command is submitted to a non-leader node. Carries the known leader, if any. */
public final class NotLeaderException extends RuntimeException {

    private final transient NodeId leaderId;

    public NotLeaderException(NodeId leaderId) {
        super(leaderId == null ? "no known leader" : "not leader; leader is " + leaderId.shortId());
        this.leaderId = leaderId;
    }

    public NodeId leaderId() {
        return leaderId;
    }
}

package com.aegisos.consensus;

import com.aegisos.core.identity.NodeId;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.StateCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raft-replicated voter/observer set with explicit configuration version.
 * Part of the Raft state machine state.
 */
public final class ClusterConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ClusterConfiguration.class);

    private volatile long version;
    private final Set<NodeId> voters = ConcurrentHashMap.newKeySet();
    private final Set<NodeId> observers = ConcurrentHashMap.newKeySet();

    public ClusterConfiguration() {
        this.version = 0;
    }


    /**
     * Initializes the configuration for join mode.
     */
    public void initJoin() {
        this.version = 0;
        this.voters.clear();
        this.observers.clear();
        log.info("Initialized join ClusterConfiguration with empty voters (version 0)");
    }

    public long version() {
        return version;
    }

    public Set<NodeId> voters() {
        return Collections.unmodifiableSet(voters);
    }

    public Set<NodeId> observers() {
        return Collections.unmodifiableSet(observers);
    }

    public boolean isVoter(NodeId node) {
        return voters.contains(node);
    }

    public boolean isObserver(NodeId node) {
        return observers.contains(node);
    }

    public synchronized void applyAddVoter(long index, StateCommand cmd) {
        try {
            NodeId nodeId = NodeId.of(cmd.getPayload().toByteArray());
            if (voters.contains(nodeId)) {
                log.info("ADD_VOTER at index {} ignored: {} is already a voter", index, nodeId.shortId());
                return; // idempotent
            }
            voters.add(nodeId);
            observers.remove(nodeId);
            version++;
            log.info("ADD_VOTER at index {} applied: {} is now a voter (version {})", index, nodeId.shortId(), version);
        } catch (Exception e) {
            log.error("Failed to apply ADD_VOTER at index {}: {}", index, e.toString());
        }
    }

    public synchronized void applyRemoveVoter(long index, StateCommand cmd) {
        try {
            NodeId nodeId = NodeId.of(cmd.getPayload().toByteArray());
            if (!voters.contains(nodeId)) {
                log.info("REMOVE_VOTER at index {} ignored: {} is not a voter", index, nodeId.shortId());
                return; // idempotent
            }
            voters.remove(nodeId);
            version++;
            log.info("REMOVE_VOTER at index {} applied: {} removed from voters (version {})", index, nodeId.shortId(), version);
        } catch (Exception e) {
            log.error("Failed to apply REMOVE_VOTER at index {}: {}", index, e.toString());
        }
    }
}

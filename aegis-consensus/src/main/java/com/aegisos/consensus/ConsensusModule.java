package com.aegisos.consensus;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.message.AegisMessage;
import com.aegisos.core.message.MessageType;
import com.aegisos.network.NetworkLayer;
import com.aegisos.proto.AppendEntries;
import com.aegisos.proto.AppendEntriesResult;
import com.aegisos.proto.ClientCommandResult;
import com.aegisos.proto.RequestVote;
import com.aegisos.proto.RequestVoteResult;
import com.aegisos.proto.StateCommand;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.InstallSnapshot;
import com.aegisos.proto.InstallSnapshotResponse;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Wires {@link RaftNode} to the {@link NetworkLayer}: serializes RPCs, dispatches inbound
 * Raft messages, and provides {@link #propose} with automatic leader forwarding so any
 * node can submit a command.
 */
public final class ConsensusModule implements RaftTransport, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConsensusModule.class);
    private static final long COMMIT_TIMEOUT_MS = 25_000;

    private final NetworkLayer network;
    private final RaftNode raftNode;
    private final ClusterStateMachine stateMachine;
    private final ClusterConfiguration clusterConfiguration;
    private final java.util.function.Function<NodeId, com.aegisos.proto.PeerStatus> peerStatusSupplier;
    private final int lagThreshold;

    public ConsensusModule(NetworkLayer network, NodeId self, Path raftDir,
                           Supplier<List<NodeId>> votingPeers,
                           Supplier<List<NodeId>> allPeers,
                           java.util.function.BooleanSupplier isVotingMember,
                           boolean bootstrap,
                           int lagThreshold,
                           java.util.function.Function<NodeId, com.aegisos.proto.PeerStatus> peerStatusSupplier) {
        this.network = network;
        this.stateMachine = new ClusterStateMachine();
        this.clusterConfiguration = new ClusterConfiguration();
        this.peerStatusSupplier = peerStatusSupplier;
        this.lagThreshold = lagThreshold;

        // Register configuration appliers.
        this.stateMachine.register(CommandType.ADD_VOTER, clusterConfiguration::applyAddVoter);
        this.stateMachine.register(CommandType.REMOVE_VOTER, clusterConfiguration::applyRemoveVoter);

        RaftLog raftLog = new RaftLog(raftDir.resolve("log.bin"));
        RaftMetadataStore metadata = new RaftMetadataStore(raftDir.resolve("meta.properties"));

        // Initialize ClusterConfiguration based on existence of log and bootstrap flag.
        boolean logExists = raftLog.lastIndex() > 0;
        if (logExists) {
            this.clusterConfiguration.initJoin();
        } else {
            if (bootstrap) {
                try {
                    StateCommand initCmd = StateCommand.newBuilder()
                            .setType(CommandType.ADD_VOTER)
                            .setPayload(ByteString.copyFrom(self.toBytes()))
                            .build();
                    raftLog.append(0, initCmd.toByteArray());
                    log.info("Appended genesis ADD_VOTER for self ({}) at index 1", self.shortId());
                } catch (Exception e) {
                    log.error("Failed to append genesis configuration", e);
                }
                this.clusterConfiguration.initJoin();
            } else {
                this.clusterConfiguration.initJoin();
            }
        }

        this.raftNode = new RaftNode(self, raftLog, metadata, this, stateMachine, votingPeers, allPeers, isVotingMember);
    }

    public void start() {
        network.registerHandler(MessageType.REQUEST_VOTE, this::onRequestVote);
        network.registerHandler(MessageType.APPEND_ENTRIES, this::onAppendEntries);
        network.registerHandler(MessageType.CLIENT_COMMAND, this::onClientCommand);
        network.registerHandler(MessageType.INSTALL_SNAPSHOT, this::onInstallSnapshot);
        raftNode.start();
        log.info("Consensus module started");
    }

    public RaftNode raftNode() {
        return raftNode;
    }

    public ClusterConfiguration clusterConfiguration() {
        return clusterConfiguration;
    }

    public ClusterStateMachine stateMachine() {
        return stateMachine;
    }

    public boolean isLeader() {
        return raftNode.isLeader();
    }

    /**
     * Eagerly replays all persisted Raft log entries through the state machine.
     * Must be called after all state-machine appliers are registered and before
     * the node is declared ready to serve traffic.
     *
     * @see RaftNode#replayCommitted()
     */
    public void replayFromLog() {
        raftNode.replayCommitted();
    }

    public NodeId leaderId() {
        return raftNode.leaderId();
    }

    /**
     * Proposes a command to the cluster. If this node is the leader it submits directly,
     * otherwise it forwards to the known leader. Completes when the command is committed.
     */
    private void validateMembershipChange(StateCommand command) {
        if (command.getType() == CommandType.ADD_VOTER) {
            NodeId target = NodeId.of(command.getPayload().toByteArray());

            // 1. Existence / Reachability: must be ALIVE or SUSPECT in Gossip
            com.aegisos.proto.PeerStatus status = peerStatusSupplier.apply(target);
            if (status != com.aegisos.proto.PeerStatus.ALIVE && status != com.aegisos.proto.PeerStatus.SUSPECT) {
                throw new IllegalArgumentException("Cannot add voter: target node is not reachable or unknown (status=" + status + ")");
            }

            // 2. Replication lag: matchIndex must be within an acceptable lag of leader's last log index.
            long leaderLastIndex = raftNode.lastLogIndex();
            long followerMatchIndex = raftNode.matchIndex(target);
            long lag = leaderLastIndex - followerMatchIndex;
            if (lag > lagThreshold) {
                throw new IllegalArgumentException("Cannot add voter: target node is lagging too far behind (lag=" + lag + " entries, limit is " + lagThreshold + ")");
            }

            // 3. Not already a voter: check ClusterConfiguration voters
            if (clusterConfiguration.isVoter(target)) {
                throw new IllegalArgumentException("Cannot add voter: target node is already a voter");
            }

            // 4. In-flight constraint: Only one configuration change may be in-flight at a time.
            if (hasUncommittedConfigChanges()) {
                throw new IllegalStateException("Cannot add voter: another membership change is already in progress (uncommitted)");
            }

        } else if (command.getType() == CommandType.REMOVE_VOTER) {
            NodeId target = NodeId.of(command.getPayload().toByteArray());

            // 1. Minimum voters: Reject REMOVE_VOTER if it would reduce voters below 1
            int currentVoterCount = clusterConfiguration.voters().size();
            if (!clusterConfiguration.isVoter(target)) {
                throw new IllegalArgumentException("Cannot remove voter: target node is not a voter");
            }
            if (currentVoterCount <= 1) {
                throw new IllegalArgumentException("Cannot remove voter: removing target would reduce voter count below 1");
            }

            // 2. In-flight constraint
            if (hasUncommittedConfigChanges()) {
                throw new IllegalStateException("Cannot remove voter: another membership change is already in progress (uncommitted)");
            }
        }
    }

    private boolean hasUncommittedConfigChanges() {
        long commitIdx = raftNode.commitIndex();
        long lastIdx = raftNode.lastLogIndex();
        for (long idx = commitIdx + 1; idx <= lastIdx; idx++) {
            com.aegisos.proto.RaftLogEntry entry = raftNode.raftLog().get(idx);
            if (entry != null) {
                try {
                    StateCommand cmd = StateCommand.parseFrom(entry.getCommand());
                    if (cmd.getType() == CommandType.ADD_VOTER || cmd.getType() == CommandType.REMOVE_VOTER) {
                        return true;
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        return false;
    }

    /**
     * Proposes a command to the cluster. If this node is the leader it submits directly,
     * otherwise it forwards to the known leader. Completes when the command is committed.
     */
    public CompletableFuture<Long> propose(StateCommand command) {
        if (raftNode.isLeader()) {
            try {
                validateMembershipChange(command);
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
            byte[] bytes = command.toByteArray();
            return raftNode.submit(bytes);
        }
        byte[] bytes = command.toByteArray();
        NodeId leader = raftNode.leaderId();
        if (leader == null) {
            return CompletableFuture.failedFuture(new NotLeaderException(null));
        }

        return network.request(leader, MessageType.CLIENT_COMMAND, bytes, COMMIT_TIMEOUT_MS + 5_000)
                .thenApply(reply -> {
                    try {
                        ClientCommandResult result = ClientCommandResult.parseFrom(reply.payload());
                        if (!result.getSuccess()) {
                            throw new NotLeaderException(result.getLeaderId().isEmpty() ? null
                                     : NodeId.of(result.getLeaderId().toByteArray()));
                        }
                        return result.getIndex();
                    } catch (CompletionException ce) {
                        throw ce;
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                });
    }

    // --- inbound handlers ------------------------------------------------

    private AegisMessage onRequestVote(AegisMessage msg) {
        try {
            RequestVoteResult result = raftNode.handleRequestVote(RequestVote.parseFrom(msg.payload()));
            return new AegisMessage(null, msg.sender(), MessageType.REQUEST_VOTE_RESULT, result.toByteArray());
        } catch (Exception e) {
            log.warn("Bad RequestVote: {}", e.toString());
            return null;
        }
    }

    private AegisMessage onAppendEntries(AegisMessage msg) {
        try {
            AppendEntriesResult result = raftNode.handleAppendEntries(AppendEntries.parseFrom(msg.payload()));
            return new AegisMessage(null, msg.sender(), MessageType.APPEND_ENTRIES_RESULT, result.toByteArray());
        } catch (Exception e) {
            log.warn("Bad AppendEntries: {}", e.toString());
            return null;
        }
    }

    private AegisMessage onInstallSnapshot(AegisMessage msg) {
        try {
            InstallSnapshotResponse result = raftNode.handleInstallSnapshot(InstallSnapshot.parseFrom(msg.payload()));
            return new AegisMessage(null, msg.sender(), MessageType.INSTALL_SNAPSHOT_RESULT, result.toByteArray());
        } catch (Exception e) {
            log.warn("Bad InstallSnapshot: {}", e.toString());
            return null;
        }
    }

    private AegisMessage onClientCommand(AegisMessage msg) {
        ClientCommandResult.Builder result = ClientCommandResult.newBuilder();
        if (!raftNode.isLeader()) {
            NodeId leader = raftNode.leaderId();
            result.setSuccess(false);
            if (leader != null) {
                result.setLeaderId(ByteString.copyFrom(leader.toBytes()));
            }
            result.setError("not leader");
        } else {
            try {
                StateCommand cmd = StateCommand.parseFrom(msg.payload());
                validateMembershipChange(cmd);
                long index = raftNode.submit(msg.payload()).get(COMMIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                result.setSuccess(true).setIndex(index);
            } catch (Exception e) {
                result.setSuccess(false).setError(e.getMessage() == null ? "commit failed" : e.getMessage());
            }
        }
        return new AegisMessage(null, msg.sender(), MessageType.CLIENT_COMMAND_RESULT, result.build().toByteArray());
    }

    // --- RaftTransport ---------------------------------------------------

    @Override
    public CompletableFuture<RequestVoteResult> sendRequestVote(NodeId peer, RequestVote request) {
        return network.request(peer, MessageType.REQUEST_VOTE, request.toByteArray(), 1_000)
                .thenApply(msg -> parse(msg.payload(), RequestVoteResult::parseFrom));
    }

    @Override
    public CompletableFuture<AppendEntriesResult> sendAppendEntries(NodeId peer, AppendEntries request) {
        return network.request(peer, MessageType.APPEND_ENTRIES, request.toByteArray(), 1_000)
                .thenApply(msg -> parse(msg.payload(), AppendEntriesResult::parseFrom));
    }

    @Override
    public CompletableFuture<InstallSnapshotResponse> sendInstallSnapshot(NodeId peer, InstallSnapshot request) {
        // Snapshot transfer can be large, use a much higher timeout
        return network.request(peer, MessageType.INSTALL_SNAPSHOT, request.toByteArray(), 30_000)
                .thenApply(msg -> parse(msg.payload(), InstallSnapshotResponse::parseFrom));
    }

    private interface ProtoParser<T> {
        T parse(byte[] bytes) throws Exception;
    }

    private static <T> T parse(byte[] bytes, ProtoParser<T> parser) {
        try {
            return parser.parse(bytes);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }

    @Override
    public void close() {
        raftNode.stop();
    }

}

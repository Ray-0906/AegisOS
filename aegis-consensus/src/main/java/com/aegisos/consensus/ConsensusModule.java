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

    public ConsensusModule(NetworkLayer network, NodeId self, Path raftDir,
                           Supplier<List<NodeId>> votingPeers,
                           Supplier<List<NodeId>> allPeers,
                           boolean isVotingMember) {
        this.network = network;
        this.stateMachine = new ClusterStateMachine();
        RaftLog raftLog = new RaftLog(raftDir.resolve("log.bin"));
        RaftMetadataStore metadata = new RaftMetadataStore(raftDir.resolve("meta.properties"));
        this.raftNode = new RaftNode(self, raftLog, metadata, this, stateMachine, votingPeers, allPeers, isVotingMember);
    }

    public void start() {
        network.registerHandler(MessageType.REQUEST_VOTE, this::onRequestVote);
        network.registerHandler(MessageType.APPEND_ENTRIES, this::onAppendEntries);
        network.registerHandler(MessageType.CLIENT_COMMAND, this::onClientCommand);
        raftNode.start();
        log.info("Consensus module started");
    }

    public RaftNode raftNode() {
        return raftNode;
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
    public CompletableFuture<Long> propose(StateCommand command) {
        byte[] bytes = command.toByteArray();
        if (raftNode.isLeader()) {
            return raftNode.submit(bytes);
        }
        NodeId leader = raftNode.leaderId();
        if (leader == null) {
            // #region agent log
            dbg("H2", "ConsensusModule.java:propose", "no known leader at propose time",
                    "isLeader=" + raftNode.isLeader());
            // #endregion
            return CompletableFuture.failedFuture(new NotLeaderException(null));
        }
        // #region agent log
        dbg("H3", "ConsensusModule.java:propose", "forwarding to leader",
                "leader=" + leader.shortId());
        // #endregion
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

    // #region agent log
    private static void dbg(String hyp, String loc, String msg, String data) {
        try {
            String line = "{\"sessionId\":\"e9aa02\",\"hypothesisId\":\"" + hyp + "\",\"location\":\"" + loc
                    + "\",\"message\":\"" + msg + "\",\"data\":{\"info\":\"" + data + "\"},\"timestamp\":"
                    + System.currentTimeMillis() + "}\n";
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("C:\\Users\\astra\\Desktop\\projects\\AgeisOS\\debug-e9aa02.log"),
                    line, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
    // #endregion
}

package com.aegisos.consensus;

import com.aegisos.consensus.election.ElectionTimer;
import com.aegisos.consensus.replication.LogReplicator;
import com.aegisos.core.identity.NodeId;
import com.aegisos.proto.AppendEntries;
import com.aegisos.proto.AppendEntriesResult;
import com.aegisos.proto.RaftLogEntry;
import com.aegisos.proto.RequestVote;
import com.aegisos.proto.RequestVoteResult;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * A single Raft consensus participant (design section 3.4). Implements leader election,
 * log replication, and commit advancement, providing the safety properties listed in
 * section 9 (election safety, log matching, leader completeness, state machine safety).
 */
public final class RaftNode {

    private static final Logger log = LoggerFactory.getLogger(RaftNode.class);

    private static final long ELECTION_MIN_MS = 150;
    private static final long ELECTION_MAX_MS = 300;
    private static final long HEARTBEAT_MS = 50;

    private final NodeId self;
    private final RaftLog raftLog;
    private final RaftMetadataStore metadata;
    private final RaftTransport transport;
    private final RaftStateMachine stateMachine;
    private final Supplier<List<NodeId>> votingPeers;
    private final Supplier<List<NodeId>> allPeers;
    private final boolean isVotingMember;

    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> Thread.ofVirtual().unstarted(r));
    private final ElectionTimer electionTimer;

    private volatile RaftRole role = RaftRole.FOLLOWER;
    private volatile NodeId leaderId;
    private long commitIndex = 0;
    private long lastApplied = 0;
    private final LogReplicator replicator;
    private final Map<Long, CompletableFuture<Long>> pending = new ConcurrentHashMap<>();

    public RaftNode(NodeId self, RaftLog raftLog, RaftMetadataStore metadata,
                    RaftTransport transport, RaftStateMachine stateMachine,
                    Supplier<List<NodeId>> votingPeers,
                    Supplier<List<NodeId>> allPeers,
                    boolean isVotingMember) {
        this.self = self;
        this.raftLog = raftLog;
        this.metadata = metadata;
        this.transport = transport;
        this.stateMachine = stateMachine;
        this.votingPeers = votingPeers;
        this.allPeers = allPeers;
        this.isVotingMember = isVotingMember;
        this.replicator = new LogReplicator();
        this.electionTimer = new ElectionTimer(scheduler, ELECTION_MIN_MS, ELECTION_MAX_MS,
                this::onElectionTimeout);
    }

    public void start() {
        electionTimer.reset();
        scheduler.scheduleAtFixedRate(this::heartbeatTick, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        log.info("Raft node {} started as FOLLOWER (term {})", self.shortId(), metadata.currentTerm());
    }

    /**
     * Eagerly replays all persisted log entries through the state machine.
     * <p>
     * Call this once after all {@link RaftStateMachine} appliers have been registered and
     * before the node is considered ready to accept work. Without this there is a ~50ms
     * startup race window where the registry is empty until the first leader heartbeat
     * triggers the normal {@link #applyCommitted()} path.
     * <p>
     * Safe to call alongside the live path: {@code lastApplied} is tracked so entries
     * already applied here are skipped when subsequent {@code applyCommitted()} calls run.
     */
    public void replayCommitted() {
        lock.lock();
        try {
            long lastOnDisk = raftLog.lastIndex();
            if (lastOnDisk == 0) {
                return; // fresh node, nothing to replay
            }
            log.info("Raft node {} replaying {} log entries from disk on startup",
                    self.shortId(), lastOnDisk - lastApplied);
            while (lastApplied < lastOnDisk) {
                lastApplied++;
                RaftLogEntry entry = raftLog.get(lastApplied);
                if (entry != null) {
                    try {
                        stateMachine.apply(entry.getIndex(), entry.getCommand().toByteArray());
                    } catch (Exception e) {
                        log.error("State machine replay failed at index {}: {}", lastApplied, e.toString());
                    }
                }
            }
            log.info("Raft node {} startup replay complete (lastApplied={})", self.shortId(), lastApplied);
        } finally {
            lock.unlock();
        }
    }

    public void stop() {
        electionTimer.stop();
        scheduler.shutdownNow();
    }

    public RaftRole role() {
        return role;
    }

    public boolean isLeader() {
        return role == RaftRole.LEADER;
    }

    public NodeId leaderId() {
        return leaderId;
    }

    public long currentTerm() {
        return metadata.currentTerm();
    }

    public long commitIndex() {
        return commitIndex;
    }

    // --- client submission ----------------------------------------------

    /** Leader-only: appends a command and completes when it is committed. */
    public CompletableFuture<Long> submit(byte[] command) {
        CompletableFuture<Long> future;
        lock.lock();
        try {
            if (role != RaftRole.LEADER) {
                return CompletableFuture.failedFuture(new NotLeaderException(leaderId));
            }
            RaftLogEntry entry = raftLog.append(metadata.currentTerm(), command);
            future = new CompletableFuture<>();
            pending.put(entry.getIndex(), future);
            log.debug("Leader {} appended entry index={} term={}", self.shortId(),
                    entry.getIndex(), entry.getTerm());
            // Single-node clusters commit immediately; otherwise replicate below.
            advanceCommit();
        } finally {
            lock.unlock();
        }
        scheduler.execute(this::broadcastAppendEntries);
        return future;
    }

    // --- election --------------------------------------------------------

    private void onElectionTimeout() {
        lock.lock();
        try {
            if (role == RaftRole.LEADER) {
                return;
            }
            if (!isVotingMember) {
                return;
            }
            startElection();
        } finally {
            lock.unlock();
        }
    }

    private void startElection() {
        long newTerm = metadata.currentTerm() + 1;
        metadata.setCurrentTerm(newTerm);
        metadata.setVotedFor(self.toHex());
        role = RaftRole.CANDIDATE;
        leaderId = null;
        electionTimer.reset();

        long lastLogIndex = raftLog.lastIndex();
        long lastLogTerm = raftLog.lastTerm();
        List<NodeId> peers = votingPeers.get();
        int clusterSize = peers.size() + 1;
        int majority = clusterSize / 2 + 1;
        AtomicInteger votes = new AtomicInteger(1); // vote for self

        log.info("Node {} starting election for term {} (cluster {}, majority {})",
                self.shortId(), newTerm, clusterSize, majority);

        RequestVote req = RequestVote.newBuilder()
                .setTerm(newTerm)
                .setCandidateId(ByteString.copyFrom(self.toBytes()))
                .setLastLogIndex(lastLogIndex)
                .setLastLogTerm(lastLogTerm)
                .build();

        if (votes.get() >= majority) {
            becomeLeader();
            return;
        }

        for (NodeId peer : peers) {
            transport.sendRequestVote(peer, req).whenComplete((result, err) -> {
                if (err != null || result == null) {
                    return;
                }
                handleVoteResponse(newTerm, majority, votes, result);
            });
        }
    }

    private void handleVoteResponse(long electionTerm, int majority, AtomicInteger votes,
                                    RequestVoteResult result) {
        lock.lock();
        try {
            if (result.getTerm() > metadata.currentTerm()) {
                stepDown(result.getTerm());
                return;
            }
            if (role != RaftRole.CANDIDATE || metadata.currentTerm() != electionTerm) {
                return;
            }
            if (result.getVoteGranted() && votes.incrementAndGet() >= majority) {
                becomeLeader();
            }
        } finally {
            lock.unlock();
        }
    }

    private void becomeLeader() {
        role = RaftRole.LEADER;
        leaderId = self;
        electionTimer.stop();
        
        // Raft paper §5.4.2: A leader cannot determine if an entry from a previous term is committed
        // until it commits an entry from its current term. Append a NO-OP to force commitIndex advancement.
        long noOpIndex = raftLog.append(metadata.currentTerm(), com.aegisos.proto.StateCommand.newBuilder()
                .setType(com.aegisos.proto.CommandType.CMD_UNKNOWN)
                .build().toByteArray()).getIndex();
                
        long next = raftLog.lastIndex() + 1;
        replicator.initLeader(votingPeers.get(), next);
        log.info("Node {} became LEADER for term {}, appended NO-OP at {}", self.shortId(), metadata.currentTerm(), noOpIndex);
        scheduler.execute(this::broadcastAppendEntries);
    }

    // --- replication / heartbeats ---------------------------------------

    private void heartbeatTick() {
        if (role == RaftRole.LEADER) {
            broadcastAppendEntries();
        }
    }

    private void broadcastAppendEntries() {
        long term;
        long leaderCommit;
        List<NodeId> peers;
        lock.lock();
        try {
            if (role != RaftRole.LEADER) {
                return;
            }
            term = metadata.currentTerm();
            leaderCommit = commitIndex;
            peers = allPeers.get();
            replicator.ensurePeers(peers, raftLog.lastIndex() + 1);
        } finally {
            lock.unlock();
        }
        for (NodeId peer : peers) {
            replicateTo(peer, term, leaderCommit);
        }
    }

    private void replicateTo(NodeId peer, long term, long leaderCommit) {
        long nextIndex = replicator.nextIndex(peer);
        long prevLogIndex = nextIndex - 1;
        long prevLogTerm = raftLog.termAt(prevLogIndex);
        List<RaftLogEntry> entries = raftLog.entriesFrom(nextIndex);

        AppendEntries req = AppendEntries.newBuilder()
                .setTerm(term)
                .setLeaderId(ByteString.copyFrom(self.toBytes()))
                .setPrevLogIndex(prevLogIndex)
                .setPrevLogTerm(prevLogTerm)
                .addAllEntries(entries)
                .setLeaderCommit(leaderCommit)
                .build();

        transport.sendAppendEntries(peer, req).whenComplete((result, err) -> {
            if (err != null || result == null) {
                return;
            }
            handleAppendResponse(peer, prevLogIndex, entries.size(), result);
        });
    }

    private void handleAppendResponse(NodeId peer, long prevLogIndex, int sent,
                                      AppendEntriesResult result) {
        lock.lock();
        try {
            if (result.getTerm() > metadata.currentTerm()) {
                stepDown(result.getTerm());
                return;
            }
            if (role != RaftRole.LEADER) {
                return;
            }
            if (result.getSuccess()) {
                long matchIndex = prevLogIndex + sent;
                replicator.onSuccess(peer, matchIndex);
                advanceCommit();
            } else {
                replicator.onFailure(peer);
            }
        } finally {
            lock.unlock();
        }
    }

    /** Advances commitIndex to the highest N replicated on a majority at the current term. */
    private void advanceCommit() {
        List<NodeId> peers = votingPeers.get();
        int clusterSize = peers.size() + 1;
        int majority = clusterSize / 2 + 1;
        for (long n = raftLog.lastIndex(); n > commitIndex; n--) {
            if (raftLog.termAt(n) != metadata.currentTerm()) {
                continue;
            }
            int count = 1; // self
            for (NodeId peer : peers) {
                if (replicator.matchIndex(peer) >= n) {
                    count++;
                }
            }
            if (count >= majority) {
                commitIndex = n;
                break;
            }
        }
        applyCommitted();
    }

    private void applyCommitted() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            RaftLogEntry entry = raftLog.get(lastApplied);
            if (entry != null) {
                try {
                    stateMachine.apply(entry.getIndex(), entry.getCommand().toByteArray());
                } catch (Exception e) {
                    log.error("State machine failed applying index {}: {}", lastApplied, e.toString());
                }
                CompletableFuture<Long> future = pending.remove(entry.getIndex());
                if (future != null) {
                    future.complete(entry.getIndex());
                }
            }
        }
    }

    private void stepDown(long newTerm) {
        metadata.setCurrentTerm(newTerm);
        role = RaftRole.FOLLOWER;
        leaderId = null;
        failPending(new NotLeaderException(null));
        electionTimer.reset();
    }

    private void failPending(Throwable t) {
        pending.values().forEach(f -> f.completeExceptionally(t));
        pending.clear();
    }

    // --- inbound RPC handlers (called by the transport) -----------------

    public RequestVoteResult handleRequestVote(RequestVote req) {
        lock.lock();
        try {
            long term = metadata.currentTerm();
            if (req.getTerm() < term) {
                return voteResult(term, false);
            }
            if (req.getTerm() > term) {
                stepDown(req.getTerm());
                term = req.getTerm();
            }
            NodeId candidate = NodeId.of(req.getCandidateId().toByteArray());
            boolean alreadyVoted = metadata.votedFor().isPresent()
                    && !metadata.votedFor().get().equals(candidate.toHex());
            boolean upToDate = raftLog.isUpToDate(req.getLastLogIndex(), req.getLastLogTerm());
            if (!alreadyVoted && upToDate) {
                metadata.setVotedFor(candidate.toHex());
                electionTimer.reset();
                return voteResult(term, true);
            }
            return voteResult(term, false);
        } finally {
            lock.unlock();
        }
    }

    public AppendEntriesResult handleAppendEntries(AppendEntries req) {
        lock.lock();
        try {
            log.info("RaftNode received AppendEntries: prevLogIndex={}, entries={}, leaderCommit={}", req.getPrevLogIndex(), req.getEntriesCount(), req.getLeaderCommit());
            long term = metadata.currentTerm();
            if (req.getTerm() < term) {
                return appendResult(term, false, raftLog.lastIndex());
            }
            if (req.getTerm() > term) {
                metadata.setCurrentTerm(req.getTerm());
                term = req.getTerm();
            }
            role = RaftRole.FOLLOWER;
            leaderId = NodeId.of(req.getLeaderId().toByteArray());
            electionTimer.reset();

            if (req.getPrevLogIndex() > 0) {
                long localPrevTerm = raftLog.termAt(req.getPrevLogIndex());
                if (req.getPrevLogIndex() > raftLog.lastIndex() || localPrevTerm != req.getPrevLogTerm()) {
                    return appendResult(term, false, raftLog.lastIndex());
                }
            }

            raftLog.appendFromLeader(req.getPrevLogIndex(), req.getEntriesList());

            if (req.getLeaderCommit() > commitIndex) {
                commitIndex = Math.min(req.getLeaderCommit(), raftLog.lastIndex());
                applyCommitted();
            }
            return appendResult(term, true, raftLog.lastIndex());
        } finally {
            lock.unlock();
        }
    }

    private static RequestVoteResult voteResult(long term, boolean granted) {
        return RequestVoteResult.newBuilder().setTerm(term).setVoteGranted(granted).build();
    }

    private static AppendEntriesResult appendResult(long term, boolean success, long matchIndex) {
        return AppendEntriesResult.newBuilder()
                .setTerm(term).setSuccess(success).setMatchIndex(matchIndex).build();
    }
}

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    private final java.util.function.BooleanSupplier isVotingMember;
    private final com.aegisos.core.observability.MetricsRegistry metricsRegistry;

    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService scheduler =
            com.aegisos.core.ExecutorRegistry.register("raftTimer", Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("aegis-raft-sched");
                return t;
            }));
    private final ElectionTimer electionTimer;

    private volatile RaftRole role = RaftRole.FOLLOWER;
    private volatile NodeId leaderId;
    private long commitIndex = 0;
    private long lastApplied = 0;
    private final LogReplicator replicator;
    private final Map<Long, CompletableFuture<Long>> pending = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentSkipListMap<Long, List<CompletableFuture<Void>>> awaitAppliedPending = new java.util.concurrent.ConcurrentSkipListMap<>();

    private long lastHeartbeatTick = System.currentTimeMillis();
    private volatile long lastLeaderMessageTick = System.currentTimeMillis();
    private long lastHeartbeatDiag = 0;
    private final AtomicInteger queuedBroadcasts = new AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicLong preVoteStarts = new java.util.concurrent.atomic.AtomicLong(0);

    // --- snapshot support ---
    private final AtomicInteger snapshotCreatedCount = new AtomicInteger(0);
    private final AtomicInteger installSnapshotSentCount = new AtomicInteger(0);
    private final AtomicInteger installSnapshotReceivedCount = new AtomicInteger(0);
    private final AtomicLong lastSnapshotDurationMs = new AtomicLong(0);

    private Path snapshotDir;
    private int snapshotEntryThreshold = 1000;
    private long snapshotSizeThresholdBytes = 64 * 1024 * 1024;

    /** Functional interface for taking a snapshot from the state machine. */
    public interface SnapshotTaker {
        byte[] take(long lastIncludedIndex, long lastIncludedTerm) throws SnapshotException;
    }

    /** Functional interface for loading a snapshot into the state machine. */
    public interface SnapshotLoader {
        void load(byte[] data) throws SnapshotException;
    }

    private SnapshotTaker snapshotTaker;
    private SnapshotLoader snapshotLoader;

    public RaftNode(NodeId self, RaftLog raftLog, RaftMetadataStore metadata,
                    RaftTransport transport, RaftStateMachine stateMachine,
                    Supplier<List<NodeId>> votingPeers,
                    Supplier<List<NodeId>> allPeers,
                    java.util.function.BooleanSupplier isVotingMember,
                    com.aegisos.core.observability.MetricsRegistry metricsRegistry) {
        this.self = self;
        this.raftLog = raftLog;
        this.metadata = metadata;
        this.transport = transport;
        this.stateMachine = stateMachine;
        this.votingPeers = votingPeers;
        this.allPeers = allPeers;
        this.isVotingMember = isVotingMember;
        this.replicator = new LogReplicator();
        this.metricsRegistry = metricsRegistry;
        this.electionTimer = new ElectionTimer(scheduler, ELECTION_MIN_MS, ELECTION_MAX_MS,
                this::onElectionTimeout);
        if (metricsRegistry != null) {
            metricsRegistry.gauge("aegis_raft_current_term").set(metadata.currentTerm());
        }
    }

    public void start() {
        electionTimer.reset();
        scheduler.scheduleAtFixedRate(this::heartbeatTick, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        log.info("Raft node {} started as FOLLOWER (term {}, snapshotIndex={})",
                self.shortId(), metadata.currentTerm(), raftLog.snapshotIndex());
    }

    /** Configure snapshot support. Must be called before start(). */
    public void configureSnapshots(Path snapshotDir, int entryThreshold, long sizeThresholdBytes,
                                    SnapshotTaker taker, SnapshotLoader loader) {
        this.snapshotDir = snapshotDir;
        this.snapshotEntryThreshold = entryThreshold;
        this.snapshotSizeThresholdBytes = sizeThresholdBytes;
        this.snapshotTaker = taker;
        this.snapshotLoader = loader;
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
            // Load snapshot if present
            if (snapshotDir != null) {
                Path snapshotFile = snapshotDir.resolve("snapshot.bin");
                if (Files.exists(snapshotFile) && snapshotLoader != null) {
                    try {
                        byte[] snapshotData = Files.readAllBytes(snapshotFile);
                        snapshotLoader.load(snapshotData);
                        lastApplied = raftLog.snapshotIndex();
                        commitIndex = raftLog.snapshotIndex();
                        log.info("Loaded snapshot on startup: lastApplied={}, commitIndex={}",
                                lastApplied, commitIndex);
                    } catch (Exception e) {
                        log.warn("Failed to load snapshot, falling back to full log replay: {}", e.toString());
                        lastApplied = 0;
                        commitIndex = 0;
                        raftLog.installSnapshot(0, 0);
                    }
                }
            }

            // Do NOT replay uncommitted log entries on startup.
            // Raft safety requires that we only apply entries when they are known to be committed.
            // Uncommitted entries that survived on disk must wait for an active leader to confirm them.
            // lastApplied and commitIndex remain at 0 (or snapshotIndex if a snapshot was loaded).
            
            long lastOnDisk = raftLog.lastIndex();
            long uncommitted = lastOnDisk - lastApplied;
            if (uncommitted > 0) {
                log.info("Raft node {} has {} uncommitted log entries on disk (from {} to {}). Waiting for leader to advance commitIndex.",
                        self.shortId(), uncommitted, lastApplied + 1, lastOnDisk);
            } else {
                log.info("Raft node {} startup complete. No uncommitted entries. (lastApplied={}, commitIndex={})", 
                        self.shortId(), lastApplied, commitIndex);
            }
        } finally {
            lock.unlock();
        }
    }

    public void stop() {
        electionTimer.stop();
        scheduler.shutdownNow();

        boolean locked = false;
        try {
            locked = lock.tryLock(2, TimeUnit.SECONDS);
            if (!locked) {
                failAwaitAppliedPending(new RuntimeException("RaftNode is shutting down"));
                log.warn("Timed out waiting for Raft lock during shutdown on {}", self.shortId());
                return;
            }
            failAwaitAppliedPending(new RuntimeException("RaftNode is shutting down"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failAwaitAppliedPending(new RuntimeException("RaftNode shutdown interrupted", e));
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }

    private void failAwaitAppliedPending(Throwable cause) {
        for (List<CompletableFuture<Void>> list : awaitAppliedPending.values()) {
            for (CompletableFuture<Void> f : list) {
                f.completeExceptionally(cause);
            }
        }
        awaitAppliedPending.clear();
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

    public List<NodeId> votingPeers() {
        return votingPeers.get();
    }

    public long commitIndex() {
        return commitIndex;
    }

    public long lastLeaderMessageTick() {
        return lastLeaderMessageTick;
    }

    public long getPreVoteStarts() {
        return preVoteStarts.get();
    }

    public long lastApplied() {
        return lastApplied;
    }

    public long lastLogIndex() {
        return raftLog.lastIndex();
    }

    public long matchIndex(NodeId peer) {
        return replicator.matchIndex(peer);
    }

    public CompletableFuture<Void> awaitApplied(long targetIndex) {
        lock.lock();
        try {
            if (lastApplied >= targetIndex) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> future = new CompletableFuture<>();
            awaitAppliedPending.computeIfAbsent(targetIndex, k -> new java.util.ArrayList<>()).add(future);
            return future;
        } finally {
            lock.unlock();
        }
    }

    public RaftLog raftLog() {
        return raftLog;
    }

    // --- client submission ----------------------------------------------

    public int getPendingCount() {
        return pending.size();
    }

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
        
        int qSize = queuedBroadcasts.getAndIncrement();
        if (qSize == 0) {
            scheduler.execute(() -> {
                queuedBroadcasts.set(0);
                this.broadcastAppendEntries();
            });
        }
        return future;
    }

    // --- election --------------------------------------------------------

    private void onElectionTimeout() {
        lock.lock();
        try {
            if (role == RaftRole.LEADER) {
                return;
            }
            if (!isVotingMember.getAsBoolean()) {
                electionTimer.reset();
                return;
            }
            startPreVote();
        } finally {
            lock.unlock();
        }
    }

    private void startPreVote() {
        // PreVote: do NOT increment term, do NOT change role.
        // Only proceed to real election if a majority grants the pre-vote.
        long preVoteTerm = metadata.currentTerm() + 1;
        long lastLogIndex = raftLog.lastIndex();
        long lastLogTerm = raftLog.lastTerm();
        List<NodeId> peers = votingPeers.get();
        int clusterSize = peers.size() + 1;
        int majority = clusterSize / 2 + 1;
        AtomicInteger votes = new AtomicInteger(1); // pre-vote for self

        preVoteStarts.incrementAndGet();
        System.out.println("RAFT_DIAG event=PRE_VOTE_STARTED node=" + self.shortId() + " preVoteTerm=" + preVoteTerm + " cluster=" + clusterSize);

        RequestVote req = RequestVote.newBuilder()
                .setTerm(preVoteTerm)
                .setCandidateId(ByteString.copyFrom(self.toBytes()))
                .setLastLogIndex(lastLogIndex)
                .setLastLogTerm(lastLogTerm)
                .build();

        electionTimer.reset();

        if (votes.get() >= majority) {
            startElection();
            return;
        }

        for (NodeId peer : peers) {
            transport.sendPreVote(peer, req).whenComplete((result, err) -> {
                if (err != null || result == null) {
                    return;
                }
                handlePreVoteResponse(preVoteTerm, majority, votes, result);
            });
        }
    }

    private void handlePreVoteResponse(long preVoteTerm, int majority,
                                       AtomicInteger votes, RequestVoteResult result) {
        lock.lock();
        try {
            // If our term changed since we sent the pre-vote, discard
            if (metadata.currentTerm() + 1 != preVoteTerm) {
                System.out.println("RAFT_DIAG event=PRE_VOTE_RESP_DISCARD_TERM node=" + self.shortId());
                return;
            }
            // If we're already leader, discard
            if (role == RaftRole.LEADER) {
                System.out.println("RAFT_DIAG event=PRE_VOTE_RESP_DISCARD_ROLE node=" + self.shortId());
                return;
            }
            // Do NOT step down on higher term — this is pre-vote
            if (result.getVoteGranted()) {
                System.out.println("RAFT_DIAG event=PRE_VOTE_RESP_GRANTED node=" + self.shortId() + " from=peer votes=" + (votes.get()+1) + "/" + majority);
                if (votes.incrementAndGet() >= majority) {
                    System.out.println("RAFT_DIAG event=PRE_VOTE_GRANTED node=" + self.shortId() + " preVoteTerm=" + preVoteTerm);
                    startElection();
                }
            } else {
                System.out.println("RAFT_DIAG event=PRE_VOTE_RESP_REJECTED node=" + self.shortId() + " respTerm=" + result.getTerm());
            }
        } finally {
            lock.unlock();
        }
    }

    private void startElection() {
        log.debug("TRANSITION: {} -> CANDIDATE", role);
        long newTerm = metadata.currentTerm() + 1;
        metadata.setCurrentTerm(newTerm);
        if (metricsRegistry != null) {
            metricsRegistry.gauge("aegis_raft_current_term").set(newTerm);
        }
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

        System.out.println("RAFT_DIAG event=ELECTION_STARTED node=" + self.shortId() + " term=" + newTerm + " cluster=" + clusterSize);
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
        log.debug("TRANSITION: {} -> LEADER", role);
        if (role != RaftRole.LEADER && metricsRegistry != null) {
            metricsRegistry.counter("aegis_raft_leader_changes_total").increment();
        }
        System.out.println("RAFT_DIAG event=LEADER_ELECTED node=" + self.shortId() + " term=" + metadata.currentTerm());
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
        advanceCommit();
        scheduler.execute(this::broadcastAppendEntries);
    }

    // --- replication / heartbeats ---------------------------------------

    private void heartbeatTick() {
        long now = System.currentTimeMillis();
        long drift = now - lastHeartbeatTick;
        if (drift > 100) {
            log.debug("Heartbeat delayed by {} ms", drift);
        }
        lastHeartbeatTick = now;

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

        if (nextIndex <= raftLog.snapshotIndex()) {
            // Follower is too far behind, need to send a snapshot
            if (snapshotDir == null) {
                log.warn("Cannot send snapshot to {}: snapshotDir not configured", peer.shortId());
                return;
            }
            Path snapshotFile = snapshotDir.resolve("snapshot.bin");
            if (!Files.exists(snapshotFile)) {
                log.warn("Cannot send snapshot to {}: snapshot file missing", peer.shortId());
                return;
            }
            try {
                byte[] data = Files.readAllBytes(snapshotFile);
                com.aegisos.proto.SnapshotFile snapshot = com.aegisos.proto.SnapshotFile.parseFrom(data);
                com.aegisos.proto.InstallSnapshot req = com.aegisos.proto.InstallSnapshot.newBuilder()
                        .setTerm(metadata.currentTerm())
                        .setLeaderId(com.google.protobuf.ByteString.copyFrom(self.toBytes()))
                        .setSnapshot(snapshot)
                        .build();

                installSnapshotSentCount.incrementAndGet();

                transport.sendInstallSnapshot(peer, req).whenComplete((result, err) -> {
                    if (err != null || result == null) {
                        return;
                    }
                    handleInstallResponse(peer, snapshot.getLastIncludedIndex(), result);
                });
            } catch (Exception e) {
                log.error("Failed to send InstallSnapshot to {}: {}", peer.shortId(), e.toString());
            }
            return;
        }

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
                replicator.onFailure(peer, result.getMatchIndex());
            }
        } finally {
            lock.unlock();
        }
    }

    private void handleInstallResponse(NodeId peer, long snapshotIndex, com.aegisos.proto.InstallSnapshotResponse result) {
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
                // Update matchIndex to the snapshot's lastIncludedIndex
                replicator.onSuccess(peer, snapshotIndex);
                advanceCommit();
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
                if (role == RaftRole.LEADER && !isVotingMember.getAsBoolean()) {
                    log.info("Leader {} was removed from voters set; stepping down to FOLLOWER", self.shortId());
                    stepDown(metadata.currentTerm());
                }
            }
        }
        
        java.util.Map<Long, List<CompletableFuture<Void>>> ready = awaitAppliedPending.headMap(lastApplied, true);
        for (List<CompletableFuture<Void>> list : ready.values()) {
            for (CompletableFuture<Void> f : list) {
                f.complete(null);
            }
        }
        ready.clear();
        
        // Check snapshot trigger (outside the apply loop for efficiency)
        maybeSnapshot();
    }

    private void maybeSnapshot() {
        if (snapshotTaker == null || snapshotDir == null) {
            return;
        }
        long entriesSinceSnapshot = lastApplied - raftLog.snapshotIndex();
        if (entriesSinceSnapshot >= snapshotEntryThreshold
                || raftLog.diskSizeBytes() >= snapshotSizeThresholdBytes) {
            triggerSnapshot();
        }
    }

    /**
     * Creates a snapshot at the current lastApplied index, writes it atomically
     * to disk, and truncates the log prefix.
     */
    public void triggerSnapshot() {
        long start = System.currentTimeMillis();
        lock.lock();
        try {
            if (snapshotTaker == null || snapshotDir == null) {
                log.warn("Snapshot not configured, skipping trigger");
                return;
            }
            long snapIndex = lastApplied;
            long snapTerm = raftLog.termAt(snapIndex);
            if (snapIndex <= raftLog.snapshotIndex()) {
                return; // already have a snapshot at or past this index
            }

            log.info("Triggering snapshot at index={}, term={}", snapIndex, snapTerm);
            byte[] snapshotData = snapshotTaker.take(snapIndex, snapTerm);

            // Atomic write: tmp -> rename
            Files.createDirectories(snapshotDir);
            Path tmpFile = snapshotDir.resolve("snapshot.tmp");
            Path binFile = snapshotDir.resolve("snapshot.bin");
            Files.write(tmpFile, snapshotData);
            Files.move(tmpFile, binFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            // Truncate log
            raftLog.truncatePrefix(snapIndex, snapTerm);
            
            snapshotCreatedCount.incrementAndGet();
            lastSnapshotDurationMs.set(System.currentTimeMillis() - start);

            log.info("Snapshot complete: index={}, term={}, size={} bytes, remaining log entries={}",
                    snapIndex, snapTerm, snapshotData.length, raftLog.entryCount());
        } catch (Exception e) {
            log.error("Failed to create snapshot: {}", e.toString(), e);
        } finally {
            lock.unlock();
        }
    }

    private void stepDown(long newTerm) {
        log.debug("TRANSITION: {} -> FOLLOWER", role);
        long current = metadata.currentTerm();
        System.out.println("RAFT_DIAG event=STEPDOWN node=" + self.shortId() + " oldRole=" + role + " oldTerm=" + current + " newTerm=" + newTerm);
        if (newTerm > current) {
            metadata.setCurrentTerm(newTerm);
            if (metricsRegistry != null) {
                metricsRegistry.gauge("aegis_raft_current_term").set(newTerm);
            }
            metadata.setVotedFor(null);
        }
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
            NodeId candidate = NodeId.of(req.getCandidateId().toByteArray());
            if (req.getTerm() < term) {
                return voteResult(term, false);
            }
            if (req.getTerm() > term) {
                System.out.println("RAFT_DIAG event=VOTE_HIGHER_TERM node=" + self.shortId() + " from=" + candidate.shortId() + " reqTerm=" + req.getTerm() + " myTerm=" + term);
                stepDown(req.getTerm());
                term = req.getTerm();
            }
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

    public RequestVoteResult handlePreVote(RequestVote req) {
        lock.lock();
        try {
            long term = metadata.currentTerm();
            // PreVote: do NOT call stepDown(). Do NOT mutate any state.

            // Reject if candidate's hypothetical term is not higher than ours
            if (req.getTerm() <= term) {
                System.out.println("RAFT_DIAG event=PRE_VOTE_REJECT_TERM node=" + self.shortId() + " reqTerm=" + req.getTerm() + " myTerm=" + term);
                return voteResult(term, false);
            }

            // Reject if we believe a leader is alive (received heartbeat recently)
            long timeSinceLeaderMsg = System.currentTimeMillis() - lastLeaderMessageTick;
            if (leaderId != null && timeSinceLeaderMsg < ELECTION_MIN_MS) {
                System.out.println("RAFT_DIAG event=PRE_VOTE_REJECT_LIVENESS node=" + self.shortId() + " leaderId=" + leaderId.shortId() + " time=" + timeSinceLeaderMsg);
                return voteResult(term, false);
            }

            // Grant if candidate's log is at least as up-to-date as ours
            boolean upToDate = raftLog.isUpToDate(req.getLastLogIndex(), req.getLastLogTerm());
            System.out.println("RAFT_DIAG event=PRE_VOTE_UPTODATE_CHECK node=" + self.shortId() + " upToDate=" + upToDate);
            return voteResult(term, upToDate);
        } finally {
            lock.unlock();
        }
    }

    public AppendEntriesResult handleAppendEntries(AppendEntries req) {
        lock.lock();
        try {
            log.debug("RaftNode received AppendEntries: prevLogIndex={}, entries={}, leaderCommit={}", req.getPrevLogIndex(), req.getEntriesCount(), req.getLeaderCommit());
            long term = metadata.currentTerm();
            if (req.getTerm() < term) {
                System.out.println("RAFT_DIAG event=AE_REJECTED node=" + self.shortId() + " leaderTerm=" + req.getTerm() + " myTerm=" + term);
                return appendResult(term, false, raftLog.lastIndex());
            }
            if (req.getTerm() > term) {
                stepDown(req.getTerm());
                term = req.getTerm();
            } else if (role == RaftRole.LEADER) {
                stepDown(term);
            } else {
                role = RaftRole.FOLLOWER;
            }
            leaderId = NodeId.of(req.getLeaderId().toByteArray());
            electionTimer.reset();
            lastLeaderMessageTick = System.currentTimeMillis();

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
            long nowHb = System.currentTimeMillis();
            if (nowHb - lastHeartbeatDiag > 1000) {
                lastHeartbeatDiag = nowHb;
                System.out.println("RAFT_DIAG event=HEARTBEAT_ACCEPTED node=" + self.shortId() + " leaderTerm=" + term + " leader=" + leaderId.shortId());
            }
            return appendResult(term, true, raftLog.lastIndex());
        } finally {
            lock.unlock();
        }
    }

    public com.aegisos.proto.InstallSnapshotResponse handleInstallSnapshot(com.aegisos.proto.InstallSnapshot req) {
        installSnapshotReceivedCount.incrementAndGet();
        lock.lock();
        try {
            long term = metadata.currentTerm();
            if (req.getTerm() < term) {
                return com.aegisos.proto.InstallSnapshotResponse.newBuilder().setTerm(term).setSuccess(false).build();
            }
            if (req.getTerm() > term) {
                stepDown(req.getTerm());
                term = req.getTerm();
            } else if (role == RaftRole.LEADER) {
                stepDown(term);
            } else {
                role = RaftRole.FOLLOWER;
            }
            leaderId = NodeId.of(req.getLeaderId().toByteArray());
            electionTimer.reset();

            com.aegisos.proto.SnapshotFile snapshot = req.getSnapshot();
            long snapIndex = snapshot.getLastIncludedIndex();
            long snapTerm = snapshot.getLastIncludedTerm();

            if (snapIndex <= commitIndex) {
                // Ignore stale snapshot
                return com.aegisos.proto.InstallSnapshotResponse.newBuilder().setTerm(term).setSuccess(true).build();
            }

            // Atomic state machine reset
            if (snapshotLoader != null) {
                snapshotLoader.load(snapshot.toByteArray());
            }

            // Write snapshot file to disk
            if (snapshotDir != null) {
                try {
                    Files.createDirectories(snapshotDir);
                    Path tmpFile = snapshotDir.resolve("snapshot.tmp");
                    Path binFile = snapshotDir.resolve("snapshot.bin");
                    Files.write(tmpFile, snapshot.toByteArray());
                    Files.move(tmpFile, binFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    log.error("Failed to persist received snapshot", e);
                }
            }

            // Reset log and state
            raftLog.installSnapshot(snapIndex, snapTerm);
            lastApplied = snapIndex;
            commitIndex = snapIndex;
            
            java.util.Map<Long, List<CompletableFuture<Void>>> ready = awaitAppliedPending.headMap(lastApplied, true);
            for (List<CompletableFuture<Void>> list : ready.values()) {
                for (CompletableFuture<Void> f : list) {
                    f.complete(null);
                }
            }
            ready.clear();

            log.info("Installed snapshot from leader {}: index={}, term={}",
                    leaderId.shortId(), snapIndex, snapTerm);

            return com.aegisos.proto.InstallSnapshotResponse.newBuilder().setTerm(term).setSuccess(true).build();
        } catch (Exception e) {
            log.error("Failed to install snapshot", e);
            return com.aegisos.proto.InstallSnapshotResponse.newBuilder().setTerm(metadata.currentTerm()).setSuccess(false).build();
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

    public int snapshotCreatedCount() { return snapshotCreatedCount.get(); }
    public int installSnapshotSentCount() { return installSnapshotSentCount.get(); }
    public int installSnapshotReceivedCount() { return installSnapshotReceivedCount.get(); }
    public long lastSnapshotDurationMs() { return lastSnapshotDurationMs.get(); }
}

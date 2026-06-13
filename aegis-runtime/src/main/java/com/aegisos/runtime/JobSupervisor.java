package com.aegisos.runtime;

import com.aegisos.consensus.ConsensusModule;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.message.MessageType;
import com.aegisos.discovery.DiscoveryService;
import com.aegisos.network.NetworkLayer;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobState;
import com.aegisos.proto.PeerStatus;
import com.aegisos.proto.RunJob;
import com.aegisos.scheduler.Scheduler;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scans RUNNING jobs on DEAD nodes and requeues them (design section 3.7, Phase 6).
 *
 * <p>Only the Raft leader performs migration, avoiding duplicate reassignments. A short
 * cooldown debounces repeated migrations of the same job.
 *
 * <p><b>Sprint 7 — Execution Leases:</b> The supervisor uses a lease-based model to decide
 * when a worker has failed. A worker must send heartbeats every 5 seconds. If no heartbeat
 * is received within {@code LEASE_DURATION_MS} (15 seconds), the lease is considered
 * expired and the job is eligible for requeue. Gossip status is used as a secondary signal
 * only. This drastically reduces false migrations during transient network issues.
 */
public final class JobSupervisor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobSupervisor.class);
    private static final long SCAN_INTERVAL_MS = 3_000;
    private static final long MIGRATION_COOLDOWN_MS = 10_000;

    /** Execution lease duration. Worker must heartbeat within this window or be considered dead. */
    private static final long LEASE_DURATION_MS = Long.getLong("aegis.lease.duration.ms", 15_000);

    /** If a QUEUED job has had no heartbeat for this long, re-dispatch it. */
    private static final long QUEUED_STALE_THRESHOLD_MS = Long.getLong("aegis.queued.stale.ms", 20_000);

    private final DiscoveryService discovery;
    private final ConsensusModule consensus;
    private final Scheduler scheduler;
    private final NetworkLayer network;
    private final NodeId self;
    private final ProcessRuntimeAgent agent;
    private final Map<String, Long> recentlyMigrated = new ConcurrentHashMap<>();
    private final Map<String, Long> lastHeartbeat = new ConcurrentHashMap<>();
    private final Map<String, Long> firstSeenQueued = new ConcurrentHashMap<>();
    private final java.util.Set<String> pendingScheduling = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aegis-job-supervisor");
                t.setDaemon(true);
                return t;
            });
    private final java.util.concurrent.ExecutorService schedulingExecutor =
            Executors.newVirtualThreadPerTaskExecutor();

    /** Tracks whether we were leader on the previous scan, to detect leadership transitions. */
    private volatile boolean wasLeader = false;
    private volatile long leadershipAcquiredTime = 0;
    public final java.util.concurrent.atomic.AtomicLong jobsLost = new java.util.concurrent.atomic.AtomicLong(0);
    public final java.util.concurrent.atomic.AtomicLong jobsRequeued = new java.util.concurrent.atomic.AtomicLong(0);

    public JobSupervisor(DiscoveryService discovery, ConsensusModule consensus,
                                Scheduler scheduler, NetworkLayer network, NodeId self,
                                ProcessRuntimeAgent agent) {
        this.discovery = discovery;
        this.consensus = consensus;
        this.scheduler = scheduler;
        this.network = network;
        this.self = self;
        this.agent = agent;
    }

    public void start() {
        network.registerHandler(MessageType.JOB_HEARTBEAT, this::onHeartbeat);
        executor.scheduleAtFixedRate(this::scanSafe, SCAN_INTERVAL_MS, SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("Job supervisor started (lease={}ms)", LEASE_DURATION_MS);
    }

    private com.aegisos.core.message.AegisMessage onHeartbeat(com.aegisos.core.message.AegisMessage msg) {
        try {
            com.aegisos.proto.JobHeartbeat hb = com.aegisos.proto.JobHeartbeat.parseFrom(msg.payload());
            agent.registry().get(hb.getJobId()).ifPresent(record -> {
                if (record.getExecutionId() == hb.getExecutionId()) {
                    lastHeartbeat.put(hb.getJobId(), System.currentTimeMillis());
                }
            });
        } catch (Exception e) {}
        return null;
    }

    private void scanSafe() {
        try {
            scan();
        } catch (Exception e) {
            log.debug("migration scan error: {}", e.toString());
        }
    }

    private void scan() {
        boolean isLeader = consensus.isLeader();
        if (!isLeader) {
            wasLeader = false;
            return;
        }

        // Detect leadership transition: clear stale state from previous leader
        if (!wasLeader) {
            log.info("Became leader — clearing migration cooldowns and heartbeat state");
            recentlyMigrated.clear();
            lastHeartbeat.clear();
            firstSeenQueued.clear();
            pendingScheduling.clear();
            wasLeader = true;
            leadershipAcquiredTime = System.currentTimeMillis();
        }

        long now = System.currentTimeMillis();
        for (JobRecord record : agent.registry().all()) {
            if (record.getState() != JobState.RUNNING && record.getState() != JobState.QUEUED
                    && record.getState() != JobState.LOST && record.getState() != JobState.PENDING) {
                continue;
            }

            String jobId = record.getSpec().getJobId();
            
            // Handle PENDING jobs — schedule them
            if (record.getState() == JobState.PENDING) {
                if (!pendingScheduling.add(jobId)) {
                    // Another virtual thread is already scheduling this job
                    continue;
                }
                schedulingExecutor.submit(() -> {
                    try {
                        NodeId target = scheduler.schedule(record.getSpec(), 1L, record.getCheckpointFileId());
                        JobRecord assignedRecord = record.toBuilder()
                                .setAssignedNodeId(ByteString.copyFrom(target.toBytes()))
                                .setState(JobState.QUEUED)
                                .setExecutionId(1L)
                                .build();
                        if (target.equals(self)) {
                            agent.dispatchLocal(assignedRecord);
                        } else {
                            network.sendAsync(target, MessageType.RUN_JOB,
                                    RunJob.newBuilder().setRecord(assignedRecord).build().toByteArray());
                        }
                        log.info("Scheduled PENDING job {} to {}", jobId, target.shortId());
                    } catch (Exception e) {
                        // It's normal if no node has capacity, we just leave it PENDING
                        log.info("Could not schedule PENDING job {}: {}", jobId, e.getMessage());
                    } finally {
                        pendingScheduling.remove(jobId);
                    }
                });
                continue;
            }

            // Handle LOST jobs using backoff
            if (record.getState() == JobState.LOST) {
                Long lastMigrated = recentlyMigrated.get(jobId);
                if (lastMigrated == null || now - lastMigrated > MIGRATION_COOLDOWN_MS) {
                    log.info("Job {} is LOST. Attempting to requeue...", jobId);
                    requeueJob(jobId, record);
                    recentlyMigrated.put(jobId, now);
                }
                continue;
            }

            // --- Normal processing for RUNNING / QUEUED ---

            NodeId assigned = NodeId.of(record.getAssignedNodeId().toByteArray());
            if (assigned.equals(self)) {
                continue;
            }

            // Lease-based failure detection (primary signal)
            boolean leaseExpired = false;
            if (record.getState() == JobState.RUNNING) {
                long last = lastHeartbeat.getOrDefault(jobId, 0L);
                if (now - leadershipAcquiredTime > LEASE_DURATION_MS) {
                    if (last == 0 || now - last > LEASE_DURATION_MS) {
                        leaseExpired = true;
                    }
                }
            }

            // For QUEUED jobs: detect stale dispatch (RUN_JOB message may have been lost)
            if (record.getState() == JobState.QUEUED) {
                long firstSeen = firstSeenQueued.computeIfAbsent(jobId, k -> now);
                if (now - firstSeen > QUEUED_STALE_THRESHOLD_MS) {
                    // Check gossip as secondary signal — only act if node appears dead
                    PeerStatus status = discovery.membership().statusOf(assigned);
                    if (status == PeerStatus.DEAD || status == PeerStatus.PEER_UNKNOWN) {
                        log.info("Job {} QUEUED on unreachable node {} for {}ms. Emitting LOST.",
                                jobId, assigned.shortId(), now - firstSeen);
                        emitLostState(jobId, record);
                        firstSeenQueued.remove(jobId);
                    }
                }
                continue;
            }

            // RUNNING + lease expired → emit LOST
            if (leaseExpired) {
                log.info("Job {} on node {}: execution lease expired ({}ms since last heartbeat). Emitting LOST.",
                        jobId, assigned.shortId(), now - lastHeartbeat.getOrDefault(jobId, 0L));
                emitLostState(jobId, record);
            }
        }
    }

    private void emitLostState(String jobId, JobRecord record) {
        try {
            com.aegisos.proto.JobUpdate lostUpdate = com.aegisos.proto.JobUpdate.newBuilder()
                    .setJobId(jobId)
                    .setExecutionId(record.getExecutionId())
                    .setState(JobState.LOST)
                    .build();
            consensus.propose(com.aegisos.proto.StateCommand.newBuilder()
                    .setType(com.aegisos.proto.CommandType.UPDATE_JOB)
                    .setPayload(lostUpdate.toByteString())
                    .build()).get(5, TimeUnit.SECONDS);
            jobsLost.incrementAndGet();

            // Test hook for Test Q (Race Condition)
            if (System.getProperty("aegis.test.delay_after_lost") != null) {
                log.info("TEST HOOK: Pausing Leader after emitting LOST for {}", jobId);
                Thread.sleep(5000);
            }
        } catch (Exception e) {
            log.warn("Failed to emit LOST state for job {}: {}", jobId, e.getMessage());
        }
    }

    private void emitTerminalFailure(String jobId, JobRecord record, String errorMsg) {
        try {
            com.aegisos.proto.JobUpdate failedUpdate = com.aegisos.proto.JobUpdate.newBuilder()
                    .setJobId(jobId)
                    .setExecutionId(record.getExecutionId())
                    .setState(JobState.FAILED)
                    .setError(errorMsg)
                    .build();
            consensus.propose(com.aegisos.proto.StateCommand.newBuilder()
                    .setType(com.aegisos.proto.CommandType.UPDATE_JOB)
                    .setPayload(failedUpdate.toByteString())
                    .build()).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Failed to emit FAILED state for job {}: {}", jobId, e.getMessage());
        }
    }

    private static final int DEFAULT_MAX_RETRIES = 3;

    private void requeueJob(String jobId, JobRecord record) {
        try {
            long nextExecutionId = record.getExecutionId() + 1;
            
            if (nextExecutionId > DEFAULT_MAX_RETRIES + 1) {
                log.warn("Job {} exceeded max retries ({}). Marking FAILED.", jobId, DEFAULT_MAX_RETRIES);
                emitTerminalFailure(jobId, record, "exceeded max retries (" + DEFAULT_MAX_RETRIES + ")");
                return;
            }

            NodeId newNode = scheduler.schedule(record.getSpec(), nextExecutionId, record.getCheckpointFileId());

            // Send cancel to old node — even if it appears dead, it might come back.
            // MUST be done AFTER schedule() commits the new executionId, so if the old node
            // is killed, its failure report is fenced.
            NodeId oldNode = NodeId.of(record.getAssignedNodeId().toByteArray());
            if (!oldNode.equals(self)) {
                log.info("Sending CANCEL_JOB to old node {} for job {} (superseded by executionId={})",
                        oldNode.shortId(), jobId, nextExecutionId);
                network.sendAsync(oldNode, MessageType.CANCEL_JOB,
                        jobId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }

            JobRecord resumed = record.toBuilder()
                    .setAssignedNodeId(ByteString.copyFrom(newNode.toBytes()))
                    .setState(JobState.QUEUED)
                    .setExecutionId(nextExecutionId)
                    .build();

            if (newNode.equals(self)) {
                agent.dispatchLocal(resumed);
            } else {
                network.sendAsync(newNode, MessageType.RUN_JOB,
                        RunJob.newBuilder().setRecord(resumed).build().toByteArray());
            }
            log.info("Job {} requeued to {} (executionId={}, checkpoint='{}')", jobId, newNode.shortId(),
                    nextExecutionId, record.getCheckpointFileId());
            jobsRequeued.incrementAndGet();
        } catch (Exception e) {
            log.warn("Failed to requeue lost job {}: {}", jobId, e.toString());
        }
    }

    @Override
    public void close() {
        log.info("JobSupervisor shutdown starting: pendingSchedulingSize={}", pendingScheduling.size());
        executor.shutdownNow();
        schedulingExecutor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
            schedulingExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("JobSupervisor shutdown complete: executor.isTerminated={}, schedulingExecutor.isTerminated={}", 
                 executor.isTerminated(), schedulingExecutor.isTerminated());
    }
}

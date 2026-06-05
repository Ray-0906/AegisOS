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
 */
public final class JobSupervisor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobSupervisor.class);
    private static final long SCAN_INTERVAL_MS = 3_000;
    private static final long MIGRATION_COOLDOWN_MS = 10_000;
    private static final long HEARTBEAT_STALE_MS = 30_000;

    private final DiscoveryService discovery;
    private final ConsensusModule consensus;
    private final Scheduler scheduler;
    private final NetworkLayer network;
    private final NodeId self;
    private final ProcessRuntimeAgent agent;
    private final Map<String, Long> recentlyMigrated = new ConcurrentHashMap<>();
    private final Map<String, Long> lastHeartbeat = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofVirtual().unstarted(r));

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
        log.info("Job supervisor started");
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
        if (!consensus.isLeader()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (JobRecord record : agent.registry().all()) {
            if (record.getState() != JobState.RUNNING && record.getState() != JobState.QUEUED && record.getState() != JobState.LOST && record.getState() != JobState.PENDING) {
                continue;
            }

            String jobId = record.getSpec().getJobId();
            
            // Handle PENDING jobs
            if (record.getState() == JobState.PENDING) {
                try {
                    NodeId target = scheduler.schedule(record.getSpec(), 1L);
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
                }
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

            // Normal processing for RUNNING / QUEUED
            NodeId assigned = NodeId.of(record.getAssignedNodeId().toByteArray());
            if (assigned.equals(self)) {
                continue;
            }

            boolean isDead = discovery.membership().statusOf(assigned) == PeerStatus.DEAD;
            boolean staleHeartbeat = false;

            if (record.getState() == JobState.RUNNING) {
                long last = lastHeartbeat.getOrDefault(record.getSpec().getJobId(), 0L);
                if (last == 0 || now - last > HEARTBEAT_STALE_MS) {
                    staleHeartbeat = true;
                }
            }

            if (isDead && (record.getState() == JobState.QUEUED || staleHeartbeat)) {
                log.info("Job {} on failed node {}. Emitting LOST state.", jobId, assigned.shortId());
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

            // Test hook for Test Q (Race Condition)
            if (System.getProperty("aegis.test.delay_after_lost") != null) {
                log.info("TEST HOOK: Pausing Leader after emitting LOST for {}", jobId);
                Thread.sleep(5000);
            }
        } catch (Exception e) {
            log.warn("Failed to emit LOST state for job {}: {}", jobId, e.getMessage());
        }
    }

    private void requeueJob(String jobId, JobRecord record) {
        try {
            long nextExecutionId = record.getExecutionId() + 1;
            NodeId newNode = scheduler.schedule(record.getSpec(), nextExecutionId);

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
        } catch (Exception e) {
            log.warn("Failed to requeue lost job {}: {}", jobId, e.toString());
        }
    }

    private boolean isAlive(NodeId id) {
        return discovery.membership().statusOf(id) == PeerStatus.ALIVE;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}

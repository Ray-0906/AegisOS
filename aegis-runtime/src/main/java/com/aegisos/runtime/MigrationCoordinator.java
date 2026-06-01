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
 * Detects jobs whose assigned node has died and migrates them to a healthy node,
 * resuming from the latest checkpoint (design section 3.7, Phase 6).
 *
 * <p>Only the Raft leader performs migration, avoiding duplicate reassignments. A short
 * cooldown debounces repeated migrations of the same job.
 */
public final class MigrationCoordinator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MigrationCoordinator.class);
    private static final long SCAN_INTERVAL_MS = 3_000;
    private static final long MIGRATION_COOLDOWN_MS = 10_000;

    private final DiscoveryService discovery;
    private final ConsensusModule consensus;
    private final Scheduler scheduler;
    private final NetworkLayer network;
    private final NodeId self;
    private final ProcessRuntimeAgent agent;
    private final Map<String, Long> recentlyMigrated = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofVirtual().unstarted(r));

    public MigrationCoordinator(DiscoveryService discovery, ConsensusModule consensus,
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
        executor.scheduleAtFixedRate(this::scanSafe, SCAN_INTERVAL_MS, SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("Migration coordinator started");
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
            if (record.getState() != JobState.RUNNING && record.getState() != JobState.QUEUED) {
                continue;
            }
            NodeId assigned = NodeId.of(record.getAssignedNodeId().toByteArray());
            if (assigned.equals(self) || isAlive(assigned)) {
                continue;
            }
            String jobId = record.getSpec().getJobId();
            Long last = recentlyMigrated.get(jobId);
            if (last != null && now - last < MIGRATION_COOLDOWN_MS) {
                continue;
            }
            migrate(record);
            recentlyMigrated.put(jobId, now);
        }
    }

    private void migrate(JobRecord record) {
        String jobId = record.getSpec().getJobId();
        try {
            log.info("Migrating job {} away from failed node {}", jobId,
                    NodeId.of(record.getAssignedNodeId().toByteArray()).shortId());
            NodeId newNode = scheduler.schedule(record.getSpec());

            JobRecord resumed = record.toBuilder()
                    .setAssignedNodeId(ByteString.copyFrom(newNode.toBytes()))
                    .setState(JobState.QUEUED)
                    .build();

            if (newNode.equals(self)) {
                agent.dispatchLocal(resumed);
            } else {
                network.sendAsync(newNode, MessageType.RUN_JOB,
                        RunJob.newBuilder().setRecord(resumed).build().toByteArray());
            }
            log.info("Job {} migrated to {} (checkpoint='{}')", jobId, newNode.shortId(),
                    record.getCheckpointFileId());
        } catch (Exception e) {
            log.warn("Failed to migrate job {}: {}", jobId, e.toString());
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

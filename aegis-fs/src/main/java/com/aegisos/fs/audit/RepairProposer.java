package com.aegisos.fs.audit;

import com.aegisos.consensus.ConsensusModule;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.util.HexUtil;
import com.aegisos.discovery.DiscoveryService;
import com.aegisos.fs.AegisFS;
import com.aegisos.fs.FileIndex;
import com.aegisos.network.NetworkLayer;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.FileMetadata;
import com.aegisos.proto.PeerStatus;
import com.aegisos.proto.RepairChunk;
import com.aegisos.proto.RepairComplete;
import com.aegisos.proto.StateCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class RepairProposer {

    private static final Logger log = LoggerFactory.getLogger(RepairProposer.class);

    private final StorageAuditScheduler auditScheduler;
    private final ConsensusModule consensus;
    private final AegisFS fileSystem;
    private final DiscoveryService discovery;
    private final NetworkLayer network;
    private final NodeId self;
    private final RepairTaskStore taskStore;
    private final long maxAgeMs;
    private final long taskTimeoutMs;

    private final Set<String> proposedRepairIds = ConcurrentHashMap.newKeySet();

    public RepairProposer(StorageAuditScheduler auditScheduler,
                          ConsensusModule consensus,
                          AegisFS fileSystem,
                          DiscoveryService discovery,
                          NetworkLayer network,
                          NodeId self,
                          RepairTaskStore taskStore,
                          long maxAgeMs,
                          long taskTimeoutMs) {
        this.auditScheduler = auditScheduler;
        this.consensus = consensus;
        this.fileSystem = fileSystem;
        this.discovery = discovery;
        this.network = network;
        this.self = self;
        this.taskStore = taskStore;
        this.maxAgeMs = maxAgeMs;
        this.taskTimeoutMs = taskTimeoutMs;
    }

    /**
     * Phase A: Evaluates recommendations and proposes REPAIR_CHUNK
     * for those that pass all guards.
     * Called after runOnce() on the leader.
     */
    public java.util.List<java.util.concurrent.CompletableFuture<RepairOutcome>> proposeRepairs() {
        // First expire stale tasks
        List<RepairTaskStore.RepairTask> expiredTasks = taskStore.expireStaleTasks(taskTimeoutMs);
        for (RepairTaskStore.RepairTask exp : expiredTasks) {
            log.info("Expired stale repair task: {}", exp.repairId());
            proposedRepairIds.remove(exp.repairId());
        }

        java.util.List<java.util.concurrent.CompletableFuture<RepairOutcome>> futures = new ArrayList<>();
        List<RepairRecommendation> recommendations = auditScheduler.getRecommendations();

        for (RepairRecommendation rec : recommendations) {
            String chunkIdHex = rec.chunkId();

            if (!isFresh(rec)) {
                futures.add(java.util.concurrent.CompletableFuture.completedFuture(new RepairOutcome(chunkIdHex, RepairOutcome.Status.STALE, "Recommendation is stale", null)));
                continue;
            }

            if (hasBlockingTask(chunkIdHex)) {
                futures.add(java.util.concurrent.CompletableFuture.completedFuture(new RepairOutcome(chunkIdHex, RepairOutcome.Status.BLOCKED, "Another repair task is already PENDING for this chunk", null)));
                continue;
            }

            if (!reVerifyDivergence(rec)) {
                futures.add(java.util.concurrent.CompletableFuture.completedFuture(new RepairOutcome(chunkIdHex, RepairOutcome.Status.NO_LONGER_NEEDED, "Divergence is no longer valid upon re-verification", null)));
                continue;
            }

            // All guards passed, propose REPAIR_CHUNK
            String repairId = UUID.randomUUID().toString();
            RepairChunk repairChunkCmd = RepairChunk.newBuilder()
                    .setRepairId(repairId)
                    .setChunkId(com.google.protobuf.ByteString.copyFrom(HexUtil.decode(chunkIdHex)))
                    .addAllEvidenceScans(rec.evidenceScans())
                    .setVerifiedAt(rec.recommendedAt())
                    .build();

            StateCommand stateCmd = StateCommand.newBuilder()
                    .setType(CommandType.REPAIR_CHUNK)
                    .setPayload(repairChunkCmd.toByteString())
                    .build();

            proposedRepairIds.add(repairId);
            try {
                java.util.concurrent.CompletableFuture<RepairOutcome> future = consensus.propose(stateCmd).handle((idx, e) -> {
                    if (e != null) {
                        log.warn("Failed to propose REPAIR_CHUNK for chunk {}: {}", chunkIdHex, e.toString());
                        proposedRepairIds.remove(repairId);
                        return new RepairOutcome(chunkIdHex, RepairOutcome.Status.PROPOSAL_FAILED, "Consensus proposal failed: " + e.getMessage(), repairId);
                    } else {
                        log.info("Successfully proposed REPAIR_CHUNK for chunk {} with repairId {}", chunkIdHex, repairId);
                        return new RepairOutcome(chunkIdHex, RepairOutcome.Status.REPAIR_PROPOSED, "REPAIR_CHUNK proposed", repairId);
                    }
                });
                futures.add(future);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                proposedRepairIds.remove(repairId);
                futures.add(java.util.concurrent.CompletableFuture.completedFuture(new RepairOutcome(chunkIdHex, RepairOutcome.Status.PROPOSAL_FAILED, "Raft executor shutting down", repairId)));
                log.debug("Failed to propose REPAIR_CHUNK for chunk {} (node shutting down)", chunkIdHex);
            } catch (Exception e) {
                proposedRepairIds.remove(repairId);
                futures.add(java.util.concurrent.CompletableFuture.completedFuture(new RepairOutcome(chunkIdHex, RepairOutcome.Status.PROPOSAL_FAILED, "Consensus proposal failed: " + e.getMessage(), repairId)));
                log.warn("Failed to propose REPAIR_CHUNK for chunk {}: {}", chunkIdHex, e.toString());
            }
        }

        return futures;
    }

    /**
     * Phase B: For each PENDING RepairTask on this leader,
     * attempt physical copy and propose REPAIR_COMPLETE on success.
     * Called after proposeRepairs() on the leader.
     */
    public java.util.List<java.util.concurrent.CompletableFuture<RepairOutcome>> executeAndComplete() {
        java.util.List<java.util.concurrent.CompletableFuture<RepairOutcome>> futures = new ArrayList<>();
        List<RepairTaskStore.RepairTask> pendingTasks = taskStore.all();

        for (RepairTaskStore.RepairTask task : pendingTasks) {
            if (task.status() != RepairTaskStore.TaskStatus.PENDING) {
                proposedRepairIds.remove(task.repairId());
                continue;
            }

            if (!proposedRepairIds.contains(task.repairId())) {
                // Ignore tasks proposed by other leaders/instances
                continue;
            }

            String chunkIdHex = task.chunkIdHex();

            // Locate file metadata containing this chunk
            FileMetadata fileMeta = null;
            com.aegisos.proto.ChunkRef chunkRef = null;
            for (FileMetadata fm : fileSystem.fileIndex().all()) {
                for (com.aegisos.proto.ChunkRef ref : fm.getChunksList()) {
                    if (HexUtil.encode(ref.getChunkId().toByteArray()).equalsIgnoreCase(chunkIdHex)) {
                        fileMeta = fm;
                        chunkRef = ref;
                        break;
                    }
                }
                if (fileMeta != null) {
                    break;
                }
            }

            if (fileMeta == null) {
                futures.add(java.util.concurrent.CompletableFuture.completedFuture(new RepairOutcome(chunkIdHex, RepairOutcome.Status.NO_LONGER_NEEDED, "File containing chunk no longer exists", task.repairId())));
                proposedRepairIds.remove(task.repairId());
                continue;
            }

            Optional<NodeId> sourceOpt = selectSource(chunkIdHex, fileMeta);
            if (sourceOpt.isEmpty()) {
                futures.add(java.util.concurrent.CompletableFuture.completedFuture(new RepairOutcome(chunkIdHex, RepairOutcome.Status.NO_SOURCE, "No healthy source node holding replica", task.repairId())));
                continue;
            }

            Optional<NodeId> targetOpt = selectTarget(chunkIdHex, fileMeta);
            if (targetOpt.isEmpty()) {
                futures.add(java.util.concurrent.CompletableFuture.completedFuture(new RepairOutcome(chunkIdHex, RepairOutcome.Status.NO_TARGET, "No valid target node available for replication", task.repairId())));
                continue;
            }

            NodeId source = sourceOpt.get();
            NodeId target = targetOpt.get();
            byte[] chunkId = HexUtil.decode(chunkIdHex);

            log.info("Executing copy for chunk {} from source {} to target {}", chunkIdHex, source.shortId(), target.shortId());
            byte[] data = fileSystem.fetchChunk(source, chunkId);
            if (data == null) {
                futures.add(java.util.concurrent.CompletableFuture.completedFuture(new RepairOutcome(chunkIdHex, RepairOutcome.Status.COPY_FAILED, "Failed to fetch chunk from source " + source.shortId(), task.repairId())));
                log.warn("Failed to fetch chunk {} from source {}", chunkIdHex, source.shortId());
                continue;
            }

            boolean copySuccess = fileSystem.replicateChunk(target, chunkId, data);
            if (!copySuccess) {
                futures.add(java.util.concurrent.CompletableFuture.completedFuture(new RepairOutcome(chunkIdHex, RepairOutcome.Status.COPY_FAILED, "Failed to store chunk on target " + target.shortId(), task.repairId())));
                log.warn("Failed to store chunk {} on target {}", chunkIdHex, target.shortId());
                continue;
            }

            // Copy succeeded, propose REPAIR_COMPLETE
            RepairComplete completeCmd = RepairComplete.newBuilder()
                    .setRepairId(task.repairId())
                    .setFileId(fileMeta.getFileId())
                    .setChunkId(com.google.protobuf.ByteString.copyFrom(chunkId))
                    .setTargetNodeId(com.google.protobuf.ByteString.copyFrom(target.toBytes()))
                    .setSourceNodeId(com.google.protobuf.ByteString.copyFrom(source.toBytes()))
                    .build();

            StateCommand stateCmd = StateCommand.newBuilder()
                    .setType(CommandType.REPAIR_COMPLETE)
                    .setPayload(completeCmd.toByteString())
                    .build();

            try {
                java.util.concurrent.CompletableFuture<RepairOutcome> future = consensus.propose(stateCmd).handle((idx, e) -> {
                    if (e != null) {
                        log.warn("Failed to propose REPAIR_COMPLETE for chunk {}: {}", chunkIdHex, e.toString());
                        return new RepairOutcome(chunkIdHex, RepairOutcome.Status.PROPOSAL_FAILED, "Failed to propose REPAIR_COMPLETE: " + e.getMessage(), task.repairId());
                    } else {
                        log.info("Successfully completed repair for chunk {}, target: {}", chunkIdHex, target.shortId());
                        proposedRepairIds.remove(task.repairId());
                        return new RepairOutcome(chunkIdHex, RepairOutcome.Status.COPY_SUCCEEDED, "REPAIR_COMPLETE proposed", task.repairId());
                    }
                });
                futures.add(future);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                futures.add(java.util.concurrent.CompletableFuture.completedFuture(new RepairOutcome(chunkIdHex, RepairOutcome.Status.PROPOSAL_FAILED, "Raft executor shutting down", task.repairId())));
                log.debug("Failed to propose REPAIR_COMPLETE for chunk {} (node shutting down)", chunkIdHex);
            } catch (Exception e) {
                futures.add(java.util.concurrent.CompletableFuture.completedFuture(new RepairOutcome(chunkIdHex, RepairOutcome.Status.PROPOSAL_FAILED, "Failed to propose REPAIR_COMPLETE: " + e.getMessage(), task.repairId())));
                log.warn("Failed to propose REPAIR_COMPLETE for chunk {}: {}", chunkIdHex, e.toString());
            }
        }

        return futures;
    }

    boolean isFresh(RepairRecommendation rec) {
        return (System.currentTimeMillis() - rec.recommendedAt()) <= maxAgeMs;
    }

    boolean hasBlockingTask(String chunkIdHex) {
        return taskStore.hasPendingRepair(chunkIdHex);
    }

    boolean reVerifyDivergence(RepairRecommendation rec) {
        String chunkIdHex = rec.chunkId();

        ChunkMetadataInventory inventory = new ChunkMetadataInventory(fileSystem.fileIndex());
        List<ChunkMetadataInventory.ChunkInventoryRecord> expectedInventoryRaw = inventory.build();
        List<ChunkMetadataInventory.ChunkInventoryRecord> expectedInventory = new ArrayList<>();
        for (ChunkMetadataInventory.ChunkInventoryRecord r : expectedInventoryRaw) {
            List<NodeId> aliveExpected = new ArrayList<>();
            for (NodeId node : r.expectedReplicaNodes()) {
                if (node.equals(self) || discovery.membership().statusOf(node) == PeerStatus.ALIVE) {
                    aliveExpected.add(node);
                }
            }
            expectedInventory.add(new ChunkMetadataInventory.ChunkInventoryRecord(
                    r.chunkIdHex(),
                    r.requiredReplicationFactor(),
                    aliveExpected
            ));
        }

        // Find expected record for this chunk
        ChunkMetadataInventory.ChunkInventoryRecord expected = null;
        for (ChunkMetadataInventory.ChunkInventoryRecord record : expectedInventory) {
            if (record.chunkIdHex().equalsIgnoreCase(chunkIdHex)) {
                expected = record;
                break;
            }
        }
        if (expected == null) {
            return false; // No longer expected (deleted)
        }

        // Step 2: Observe physical state
        ObservedStateCollector collector = new ObservedStateCollector();
        Map<NodeId, Set<String>> observed = collector.observeRemoteState(
                network, discovery.membership(), self, fileSystem.chunkStore());

        // Count global physical presence
        int globalPhysicalCount = 0;
        for (Set<String> nodeChunks : observed.values()) {
            if (nodeChunks != null && nodeChunks.contains(chunkIdHex)) {
                globalPhysicalCount++;
            }
        }

        // Find missing nodes
        List<NodeId> missingNodes = new ArrayList<>();
        for (NodeId expectedNode : expected.expectedReplicaNodes()) {
            Set<String> chunksOnNode = observed.get(expectedNode);
            if (chunksOnNode == null || !chunksOnNode.contains(chunkIdHex)) {
                missingNodes.add(expectedNode);
            }
        }

        // If metadata replication factor is met, it's no longer divergent
        if (globalPhysicalCount >= expected.requiredReplicationFactor()) {
            return false;
        }

        return true;
    }

    public Optional<NodeId> selectSource(String chunkIdHex, FileMetadata metadata) {
        com.aegisos.proto.ChunkRef chunkRef = null;
        for (com.aegisos.proto.ChunkRef ref : metadata.getChunksList()) {
            if (HexUtil.encode(ref.getChunkId().toByteArray()).equalsIgnoreCase(chunkIdHex)) {
                chunkRef = ref;
                break;
            }
        }
        if (chunkRef == null) {
            return Optional.empty();
        }

        ObservedStateCollector collector = new ObservedStateCollector();
        Map<NodeId, Set<String>> observed = collector.observeRemoteState(
                network, discovery.membership(), self, fileSystem.chunkStore());

        for (com.google.protobuf.ByteString nodeBytes : chunkRef.getNodeIdsList()) {
            NodeId node = NodeId.of(nodeBytes.toByteArray());
            if (discovery.membership().statusOf(node) == PeerStatus.ALIVE) {
                Set<String> chunks = observed.get(node);
                if (chunks != null && chunks.contains(chunkIdHex)) {
                    return Optional.of(node);
                }
            }
        }
        return Optional.empty();
    }

    public Optional<NodeId> selectTarget(String chunkIdHex, FileMetadata metadata) {
        com.aegisos.proto.ChunkRef chunkRef = null;
        for (com.aegisos.proto.ChunkRef ref : metadata.getChunksList()) {
            if (HexUtil.encode(ref.getChunkId().toByteArray()).equalsIgnoreCase(chunkIdHex)) {
                chunkRef = ref;
                break;
            }
        }
        if (chunkRef == null) {
            return Optional.empty();
        }

        ObservedStateCollector collector = new ObservedStateCollector();
        Map<NodeId, Set<String>> observed = collector.observeRemoteState(
                network, discovery.membership(), self, fileSystem.chunkStore());

        var voters = consensus.clusterConfiguration().voters();
        for (NodeId voter : voters) {
            if (discovery.membership().statusOf(voter) == PeerStatus.ALIVE) {
                Set<String> chunks = observed.get(voter);
                if (chunks == null || !chunks.contains(chunkIdHex)) {
                    return Optional.of(voter);
                }
            }
        }
        return Optional.empty();
    }
}

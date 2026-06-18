package com.aegisos.fs.audit;

import com.aegisos.core.identity.NodeId;
import com.aegisos.discovery.DiscoveryService;
import com.aegisos.fs.AegisFS;
import com.aegisos.network.NetworkLayer;
import com.aegisos.proto.PeerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodically runs storage audits and drives the full
 * Observation → Verification → Recommendation pipeline.
 *
 * <p>Scheduling: hardcoded 60-second interval.
 * <p>Tests call {@link #runOnce()} directly; no manual trigger endpoint is exposed.
 *
 * <p>On each cycle, {@code currentVerifications} and {@code currentRecommendations}
 * are replaced atomically. Recommendations are NOT latched — a healed divergence
 * causes all three (divergence, verification, recommendation) to disappear.
 * {@link AuditReportStore} retains historical scans even after healing.
 *
 * Part of the Verification + Recommendation pipeline (Sprint 4).
 */
public final class StorageAuditScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(StorageAuditScheduler.class);

    private final long intervalSeconds;
    private final AegisFS fileSystem;
    private final DiscoveryService discovery;
    private final NetworkLayer network;
    private final NodeId self;

    private final java.util.function.BooleanSupplier isLeader;
    private final AuditReportStore store;
    private final AtomicLong scanCounter = new AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicBoolean isScanning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.ExecutorService workerExecutor = java.util.concurrent.Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("aegis-audit-worker-", 0).factory());

    private volatile List<VerificationResult> currentVerifications = Collections.emptyList();
    private volatile List<RepairRecommendation> currentRecommendations = Collections.emptyList();
    private volatile RepairProposer repairProposer;
    private volatile List<RepairOutcome> currentRepairOutcomes = Collections.emptyList();
    private final List<RepairOutcome> historicalRepairOutcomes = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = Thread.ofVirtual().unstarted(r);
                t.setName("aegis-audit-scheduler");
                return t;
            });

    public StorageAuditScheduler(AegisFS fileSystem, DiscoveryService discovery,
                                 NetworkLayer network, NodeId self,
                                 java.util.function.BooleanSupplier isLeader,
                                 long intervalSeconds) {
        this.fileSystem = fileSystem;
        this.discovery = discovery;
        this.network = network;
        this.self = self;
        this.isLeader = isLeader;
        this.intervalSeconds = intervalSeconds;
        this.store = new AuditReportStore();
    }

    /**
     * Starts periodic audit scanning.
     */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::runOnceSafe, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Storage audit scheduler started (interval {}s)", intervalSeconds);
    }

    private void runOnceSafe() {
        if (!isScanning.compareAndSet(false, true)) {
            log.debug("Skipping audit cycle: previous scan still running");
            return;
        }
        workerExecutor.submit(() -> {
            try {
                runOnce();
            } catch (Exception e) {
                log.warn("Storage audit cycle error: {}", e.toString());
            } finally {
                isScanning.set(false);
            }
        });
    }

    /**
     * Executes a single audit cycle synchronously.
     *
     * <ol>
     *   <li>Collect expected inventory and observed state.</li>
     *   <li>Detect divergences.</li>
     *   <li>Build {@link AuditReport} and add to history.</li>
     *   <li>For each divergence, run {@link StorageVerifier}.</li>
     *   <li>If verified, construct {@link RepairRecommendation}.</li>
     *   <li>Replace current verifications and recommendations atomically.</li>
     * </ol>
     */
    public void runOnce() {
        if (!isLeader.getAsBoolean()) {
            currentVerifications = Collections.emptyList();
            currentRecommendations = Collections.emptyList();
            currentRepairOutcomes = Collections.emptyList();
            log.debug("Skip audit scan: not the consensus leader");
            return;
        }
        long scanId = scanCounter.incrementAndGet();
        long timestamp = System.currentTimeMillis();

        // Step 1: Collect expected inventory (filtering only alive expected nodes)
        ChunkMetadataInventory inventory = new ChunkMetadataInventory(fileSystem.fileIndex());
        List<ChunkMetadataInventory.ChunkInventoryRecord> expectedRaw = inventory.build();
        List<ChunkMetadataInventory.ChunkInventoryRecord> expected = new ArrayList<>();
        for (ChunkMetadataInventory.ChunkInventoryRecord rec : expectedRaw) {
            List<NodeId> aliveExpected = new ArrayList<>();
            for (NodeId node : rec.expectedReplicaNodes()) {
                if (node.equals(self) || discovery.membership().statusOf(node) == PeerStatus.ALIVE) {
                    aliveExpected.add(node);
                }
            }
            expected.add(new ChunkMetadataInventory.ChunkInventoryRecord(
                    rec.chunkIdHex(),
                    rec.requiredReplicationFactor(),
                    aliveExpected
            ));
        }

        // Step 2: Observe physical state
        ObservedStateCollector collector = new ObservedStateCollector();
        Map<NodeId, Set<String>> observed =
                collector.observeRemoteState(network, discovery.membership(), self, fileSystem.chunkStore());

        // Step 3: Detect divergences
        DivergenceReportGenerator generator = new DivergenceReportGenerator();
        List<DivergenceReportGenerator.UnderReplicatedChunk> divergences =
                generator.detectUnderReplicated(expected, observed);

        // Step 4: Build frozen audit report and add to history
        AuditReport currentReport = new AuditReport(scanId, timestamp, divergences);
        store.addReport(currentReport);

        if (divergences.isEmpty()) {
            currentVerifications = Collections.emptyList();
            currentRecommendations = Collections.emptyList();
            currentRepairOutcomes = Collections.emptyList();
            log.debug("Audit scan {} complete: no divergences", scanId);
            return;
        }

        // Step 5: Verify each divergence
        StorageVerifier verifier = new StorageVerifier(
                store, collector, inventory, discovery, network, self, fileSystem.chunkStore());

        List<VerificationResult> newVerifications = new ArrayList<>();
        List<RepairRecommendation> newRecommendations = new ArrayList<>();

        for (DivergenceReportGenerator.UnderReplicatedChunk divergence : divergences) {
            VerificationResult result = verifier.verify(divergence, currentReport, scanId);
            newVerifications.add(result);

            if (result.status() == VerificationStatus.VERIFIED) {
                VerifiedDivergence verified = new VerifiedDivergence(
                        result.chunkId(),
                        "UNDER_REPLICATED",
                        result.evidenceScans(),
                        timestamp
                );

                RepairRecommendation recommendation = new RepairRecommendation(
                        verified.chunkId(),
                        verified.divergenceType(),
                        verified.evidenceScans(),
                        timestamp
                );
                newRecommendations.add(recommendation);

                log.info("Audit scan {}: chunk {} VERIFIED (evidence: scans {})",
                        scanId, result.chunkId(), result.evidenceScans());
            } else {
                log.debug("Audit scan {}: chunk {} -> {}: {}",
                        scanId, result.chunkId(), result.status(), result.details());
            }
        }

        // Step 6: Atomic replace
        currentVerifications = Collections.unmodifiableList(newVerifications);
        currentRecommendations = Collections.unmodifiableList(newRecommendations);

        // Step 7 & 8: Propose and execute repairs
        if (repairProposer != null && isLeader.getAsBoolean()) {
            List<RepairOutcome> phaseA = repairProposer.proposeRepairs();
            List<RepairOutcome> phaseB = repairProposer.executeAndComplete();
            List<RepairOutcome> merged = new ArrayList<>(phaseA);
            merged.addAll(phaseB);
            currentRepairOutcomes = Collections.unmodifiableList(merged);
            historicalRepairOutcomes.addAll(merged);
        } else {
            currentRepairOutcomes = Collections.emptyList();
        }

        log.info("Audit scan {} complete: {} divergences, {} verified, {} recommendations, {} repair outcomes",
                scanId, divergences.size(), newRecommendations.size(), newRecommendations.size(), currentRepairOutcomes.size());
    }

    public List<VerificationResult> getVerifications() {
        return currentVerifications;
    }

    public List<RepairRecommendation> getRecommendations() {
        return currentRecommendations;
    }

    public AuditReportStore getStore() {
        return store;
    }

    public void setRepairProposer(RepairProposer proposer) {
        this.repairProposer = proposer;
    }

    public List<RepairOutcome> getRepairOutcomes() {
        return currentRepairOutcomes;
    }

    public List<RepairOutcome> getHistoricalRepairOutcomes() {
        return new ArrayList<>(historicalRepairOutcomes);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}

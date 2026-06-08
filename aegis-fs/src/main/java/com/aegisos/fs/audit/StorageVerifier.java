package com.aegisos.fs.audit;

import com.aegisos.core.identity.NodeId;
import com.aegisos.discovery.DiscoveryService;
import com.aegisos.fs.ChunkStore;
import com.aegisos.network.NetworkLayer;
import com.aegisos.proto.PeerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies that a divergence is real, persistent, and current.
 *
 * <p>This is the key correctness component of Sprint 4. It does NOT trust
 * prior observations alone. At verification time it:
 * <ol>
 *   <li>Checks historical persistence via {@link AuditReportStore}</li>
 *   <li>Checks node liveness via {@link DiscoveryService}</li>
 *   <li>Re-observes physical reality via {@link ObservedStateCollector} +
 *       {@link ChunkMetadataInventory}</li>
 *   <li>Compares current reality against the frozen divergence snapshot</li>
 * </ol>
 *
 * <p>IMPORT RESTRICTION: This class MUST NOT import ConsensusModule, RaftNode,
 * or any state-mutating component.
 *
 * Part of the Verification + Recommendation pipeline (Sprint 4).
 */
public final class StorageVerifier {

    private static final Logger log = LoggerFactory.getLogger(StorageVerifier.class);

    /** Number of consecutive scans required to consider a divergence persistent. */
    static final int REQUIRED_CONSECUTIVE_SCANS = 2;

    private final AuditReportStore store;
    private final ObservedStateCollector observedStateCollector;
    private final ChunkMetadataInventory inventory;
    private final DiscoveryService discovery;
    private final NetworkLayer network;
    private final NodeId self;
    private final ChunkStore chunkStore;

    public StorageVerifier(AuditReportStore store,
                           ObservedStateCollector observedStateCollector,
                           ChunkMetadataInventory inventory,
                           DiscoveryService discovery,
                           NetworkLayer network,
                           NodeId self,
                           ChunkStore chunkStore) {
        this.store = store;
        this.observedStateCollector = observedStateCollector;
        this.inventory = inventory;
        this.discovery = discovery;
        this.network = network;
        this.self = self;
        this.chunkStore = chunkStore;
    }

    /**
     * Verifies a single divergence.
     *
     * @param divergence   the divergence from the current scan
     * @param currentReport the frozen audit report snapshot being validated
     * @param currentScanId the scan ID at which this verification is performed
     * @return the verification result with enum status and evidence chain
     */
    public VerificationResult verify(DivergenceReportGenerator.UnderReplicatedChunk divergence,
                                     AuditReport currentReport,
                                     long currentScanId) {

        String chunkId = divergence.chunkIdHex;

        // Step 1: Persistence check — has this divergence appeared in enough consecutive scans?
        if (!store.hasPersisted(chunkId, REQUIRED_CONSECUTIVE_SCANS)) {
            return new VerificationResult(
                    chunkId,
                    VerificationStatus.INSUFFICIENT_HISTORY,
                    "Divergence has not persisted for " + REQUIRED_CONSECUTIVE_SCANS + " consecutive scans",
                    currentScanId,
                    Collections.emptyList()
            );
        }

        List<Long> evidenceScans = store.evidenceScansFor(chunkId, REQUIRED_CONSECUTIVE_SCANS);

        // Step 2: Membership check — are the missing nodes actually ALIVE?
        for (NodeId missingNode : divergence.missingFromNodes) {
            PeerStatus status = discovery.membership().statusOf(missingNode);
            if (status != PeerStatus.ALIVE) {
                return new VerificationResult(
                        chunkId,
                        VerificationStatus.NODE_UNAVAILABLE,
                        "Node " + missingNode.shortId() + " has status " + status.name()
                                + "; cannot verify divergence while node is unavailable",
                        currentScanId,
                        evidenceScans
                );
            }
        }

        // Step 3: Re-observe physical reality NOW
        try {
            Map<NodeId, Set<String>> freshObserved =
                    observedStateCollector.observeRemoteState(network, discovery.membership(), self, chunkStore);
            List<ChunkMetadataInventory.ChunkInventoryRecord> freshExpected = inventory.build();

            DivergenceReportGenerator generator = new DivergenceReportGenerator();
            List<DivergenceReportGenerator.UnderReplicatedChunk> freshDivergences =
                    generator.detectUnderReplicated(freshExpected, freshObserved);

            // Is the chunk still under-replicated?
            DivergenceReportGenerator.UnderReplicatedChunk freshMatch = null;
            for (DivergenceReportGenerator.UnderReplicatedChunk d : freshDivergences) {
                if (d.chunkIdHex.equals(chunkId)) {
                    freshMatch = d;
                    break;
                }
            }

            if (freshMatch == null) {
                // Chunk healed before verification completed
                return new VerificationResult(
                        chunkId,
                        VerificationStatus.NO_LONGER_DIVERGENT,
                        "Chunk is no longer under-replicated upon re-observation",
                        currentScanId,
                        evidenceScans
                );
            }

            // Compare the fresh observation against the frozen snapshot
            if (freshMatch.actualPhysicalCount != divergence.actualPhysicalCount
                    || freshMatch.requiredReplicationFactor != divergence.requiredReplicationFactor) {
                return new VerificationResult(
                        chunkId,
                        VerificationStatus.OBSERVATION_MISMATCH,
                        "Fresh observation differs from historical: historical RF-gap="
                                + (divergence.requiredReplicationFactor - divergence.actualPhysicalCount)
                                + " fresh RF-gap="
                                + (freshMatch.requiredReplicationFactor - freshMatch.actualPhysicalCount),
                        currentScanId,
                        evidenceScans
                );
            }

        } catch (Exception e) {
            log.warn("Re-observation failed during verification for chunk {}: {}", chunkId, e.toString());
            return new VerificationResult(
                    chunkId,
                    VerificationStatus.OBSERVATION_MISMATCH,
                    "Re-observation failed: " + e.getMessage(),
                    currentScanId,
                    evidenceScans
            );
        }

        // Step 4: All checks passed
        return new VerificationResult(
                chunkId,
                VerificationStatus.VERIFIED,
                "Divergence verified: persistent across " + evidenceScans.size()
                        + " scans, all missing nodes ALIVE, current observation confirms",
                currentScanId,
                evidenceScans
        );
    }
}

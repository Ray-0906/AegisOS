package com.aegisos.fs.audit;

import com.aegisos.core.identity.NodeId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates an actionable report comparing authoritative state vs observed physical state.
 * 
 * Part of the Storage Audit Framework (Sprint 2 - Step 3).
 * Strictly read-only.
 */
public class DivergenceReportGenerator {

    /**
     * Finds chunks that are UNDER_REPLICATED based on the following strict criteria:
     * 1. It is physically absent from a node that the authoritative metadata claims should have it.
     * AND
     * 2. The total number of nodes physically possessing the chunk (anywhere in the cluster) 
     *    is strictly less than the metadata's replication factor.
     */
    public List<UnderReplicatedChunk> detectUnderReplicated(
            List<ChunkMetadataInventory.ChunkInventoryRecord> expectedInventory,
            Map<NodeId, Set<String>> observedState) {

        List<UnderReplicatedChunk> divergences = new ArrayList<>();

        for (ChunkMetadataInventory.ChunkInventoryRecord expected : expectedInventory) {
            
            // Calculate total global physical presence
            int globalPhysicalCount = 0;
            for (Set<String> nodeChunks : observedState.values()) {
                if (nodeChunks != null && nodeChunks.contains(expected.chunkIdHex())) {
                    globalPhysicalCount++;
                }
            }

            // Identify which *expected* nodes are missing it
            List<NodeId> missingFrom = new ArrayList<>();
            for (NodeId expectedNode : expected.expectedReplicaNodes()) {
                Set<String> chunksOnNode = observedState.get(expectedNode);
                if (chunksOnNode == null || !chunksOnNode.contains(expected.chunkIdHex())) {
                    missingFrom.add(expectedNode);
                }
            }

            // Apply the two conditions
            if (!missingFrom.isEmpty() && globalPhysicalCount < expected.requiredReplicationFactor()) {
                divergences.add(new UnderReplicatedChunk(
                        expected.chunkIdHex(),
                        expected.requiredReplicationFactor(),
                        globalPhysicalCount,
                        missingFrom
                ));
            }
        }

        return divergences;
    }

    public static class UnderReplicatedChunk {
        public final String chunkIdHex;
        public final int requiredReplicationFactor;
        public final int actualPhysicalCount;
        public final List<NodeId> missingFromNodes;

        public UnderReplicatedChunk(String chunkIdHex, int requiredReplicationFactor, int actualPhysicalCount, List<NodeId> missingFromNodes) {
            this.chunkIdHex = chunkIdHex;
            this.requiredReplicationFactor = requiredReplicationFactor;
            this.actualPhysicalCount = actualPhysicalCount;
            this.missingFromNodes = missingFromNodes;
        }
    }
}

package com.aegisos.fs.audit;

import com.aegisos.fs.ChunkStore;

import java.util.HashSet;
import java.util.Set;

/**
 * Gathers the physically observed state from nodes.
 * 
 * Part of the Storage Audit Framework (Sprint 2 - Step 2).
 * Strictly read-only. Cannot trigger repairs or modify state.
 * 
 * IMPORT RESTRICTION: This class MUST NOT import ConsensusModule, RaftNode, 
 * FileIndex mutators, or AntiEntropyManager.
 */
public class ObservedStateCollector {

    /**
     * Step 2A: Local observation
     * Inspects the local filesystem (ChunkStore) to report which chunks physically exist.
     * 
     * @param store The local ChunkStore instance
     * @return A set of chunk ID hex strings physically present on disk
     */
    public Set<String> observeLocalState(ChunkStore store) {
        return new HashSet<>(store.listChunkIds());
    }

    /**
     * Step 2B: Remote observation
     * Asks all cluster nodes for their chunk inventory via read-only network queries.
     * 
     * @param network The NetworkLayer to send queries over
     * @param membership The MembershipList containing known peers
     * @param self This node's ID (to skip or query locally if preferred, though NetworkLayer handles loopback)
     * @return A map of NodeId to the set of chunk IDs physically present on that node
     */
    public java.util.Map<com.aegisos.core.identity.NodeId, Set<String>> observeRemoteState(
            com.aegisos.network.NetworkLayer network,
            com.aegisos.discovery.gossip.MembershipList membership,
            com.aegisos.core.identity.NodeId self,
            ChunkStore localStore) {
            
        java.util.Map<com.aegisos.core.identity.NodeId, Set<String>> result = new java.util.HashMap<>();
        
        // Add self immediately via local inspection (faster and avoids loopback)
        result.put(self, observeLocalState(localStore));

        com.aegisos.proto.ClientQuery query = com.aegisos.proto.ClientQuery.newBuilder()
                .setType(com.aegisos.proto.QueryType.LOCAL_CHUNKS)
                .build();

        java.util.Map<com.aegisos.core.identity.NodeId, java.util.concurrent.CompletableFuture<com.aegisos.core.message.AegisMessage>> futures = new java.util.HashMap<>();
        
        for (com.aegisos.proto.PeerEntry peer : membership.allPeers()) {
            com.aegisos.core.identity.NodeId targetId = com.aegisos.core.identity.NodeId.of(peer.getNodeId().toByteArray());
            
            if (targetId.equals(self)) continue; // Already did local

            futures.put(targetId, network.request(
                    targetId,
                    com.aegisos.core.message.MessageType.CLIENT_QUERY,
                    query.toByteArray()
            ));
        }

        try {
            java.util.concurrent.CompletableFuture.allOf(futures.values().toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            // Best effort: if some timeout, we just collect what we have
        }

        for (java.util.Map.Entry<com.aegisos.core.identity.NodeId, java.util.concurrent.CompletableFuture<com.aegisos.core.message.AegisMessage>> entry : futures.entrySet()) {
            try {
                if (!entry.getValue().isDone() || entry.getValue().isCompletedExceptionally() || entry.getValue().isCancelled()) {
                    continue; // Skip node on timeout or error
                }
                
                com.aegisos.core.message.AegisMessage response = entry.getValue().get();
                com.aegisos.proto.ClientQueryResult res = com.aegisos.proto.ClientQueryResult.parseFrom(response.payload());
                if (!res.getError().isEmpty()) {
                    continue; // Skip node on error
                }
                
                String chunksCsv = res.getPayload().toStringUtf8();
                Set<String> chunks = new HashSet<>();
                if (!chunksCsv.isEmpty()) {
                    chunks.addAll(java.util.Arrays.asList(chunksCsv.split(",")));
                }
                
                result.put(entry.getKey(), chunks);
            } catch (Exception e) {
                // Ignore parsing errors
            }
        }
        return result;
    }
}

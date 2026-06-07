package com.aegisos.fs.audit;

import com.aegisos.core.identity.NodeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DivergenceReportGeneratorTest {

    @Test
    void testUnderReplicated() {
        NodeId nodeA = NodeId.of(new byte[32]);
        NodeId nodeB = NodeId.of(new byte[]{1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1});
        NodeId nodeC = NodeId.of(new byte[]{2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2});

        String chunk1 = "chunk1hex";
        String chunk2 = "chunk2hex";

        // Expected Inventory
        List<ChunkMetadataInventory.ChunkInventoryRecord> expected = List.of(
            // chunk1 is expected on nodeA and nodeB
            new ChunkMetadataInventory.ChunkInventoryRecord(chunk1, 2, List.of(nodeA, nodeB)),
            // chunk2 is expected on nodeA, nodeB, nodeC
            new ChunkMetadataInventory.ChunkInventoryRecord(chunk2, 3, List.of(nodeA, nodeB, nodeC))
        );

        // Observed State
        Map<NodeId, Set<String>> observed = Map.of(
            nodeA, Set.of(chunk1, chunk2),
            nodeB, Set.of(chunk1),          // Missing chunk2
            nodeC, Set.of()                 // Missing chunk2
        );

        DivergenceReportGenerator generator = new DivergenceReportGenerator();
        List<DivergenceReportGenerator.UnderReplicatedChunk> result = generator.detectUnderReplicated(expected, observed);

        // chunk1 should NOT be under-replicated (on nodeA and nodeB, global count = 2)
        // chunk2 SHOULD be under-replicated (only on nodeA, global count = 1, expected 3)
        assertEquals(1, result.size());
        assertEquals(chunk2, result.get(0).chunkIdHex);
        assertEquals(1, result.get(0).actualPhysicalCount);
        assertEquals(2, result.get(0).missingFromNodes.size());
    }

    @Test
    void testDisplacedNotUnderReplicated() {
        NodeId nodeA = NodeId.of(new byte[32]);
        NodeId nodeB = NodeId.of(new byte[]{1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1});
        NodeId nodeC = NodeId.of(new byte[]{2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2});

        String chunk1 = "chunk1hex";

        List<ChunkMetadataInventory.ChunkInventoryRecord> expected = List.of(
            new ChunkMetadataInventory.ChunkInventoryRecord(chunk1, 2, List.of(nodeA, nodeB))
        );

        // Observed State: chunk1 is missing from nodeA, but it's physically present on nodeB and nodeC
        Map<NodeId, Set<String>> observed = Map.of(
            nodeA, Set.of(),
            nodeB, Set.of(chunk1),
            nodeC, Set.of(chunk1)
        );

        DivergenceReportGenerator generator = new DivergenceReportGenerator();
        List<DivergenceReportGenerator.UnderReplicatedChunk> result = generator.detectUnderReplicated(expected, observed);

        // According to strict rules, actual physical count is 2, required is 2. So it's NOT under-replicated.
        assertEquals(0, result.size());
    }
}

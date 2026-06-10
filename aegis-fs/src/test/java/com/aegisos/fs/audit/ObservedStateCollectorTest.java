package com.aegisos.fs.audit;

import com.aegisos.core.util.HexUtil;
import com.aegisos.fs.ChunkStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ObservedStateCollectorTest {

    private Path tempDir;
    private ChunkStore store;

    @BeforeEach
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory("aegis-obs-test-");
        store = new ChunkStore(tempDir);
    }

    @AfterEach
    public void teardown() throws IOException {
        deleteRecursive(tempDir.toFile());
    }

    private void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }

    @Test
    public void testObserveLocalState() {
        byte[] chunk1 = new byte[]{1, 1, 1};
        byte[] chunk2 = new byte[]{2, 2, 2};
        byte[] chunk3 = new byte[]{3, 3, 3};

        // Write three chunks
        store.put(chunk1, new byte[]{9, 9});
        store.put(chunk2, new byte[]{8, 8});
        store.put(chunk3, new byte[]{7, 7});

        // Quarantine one chunk (moves it out of the main directory)
        store.quarantine(chunk2);

        ObservedStateCollector collector = new ObservedStateCollector();
        Set<String> observed = collector.observeLocalState(store);

        // Should observe chunk1 and chunk3, but not chunk2 (quarantined)
        assertEquals(2, observed.size());
        assertTrue(observed.contains(HexUtil.encode(chunk1)));
        assertTrue(observed.contains(HexUtil.encode(chunk3)));
    }
}

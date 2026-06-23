package com.aegisos.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class ArtifactUploadAndDownloadTest {

    private ClusterHarness cluster;

    @BeforeEach
    public void setup() throws Exception {
        cluster = new ClusterHarness();
        cluster.start(3);
    }

    @AfterEach
    public void teardown() throws Exception {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    public void testArtifactUploadAndDownload() throws Exception {
        byte[] originalData = "test artifact payload".getBytes();

        String sha256 = cluster.node(0).api().getProcessManager().uploadArtifact(originalData);
        assertNotNull(sha256);
        assertFalse(sha256.isEmpty());

        // Download via the same node
        byte[] downloadedData = cluster.node(0).api().getProcessManager().downloadArtifact(sha256);
        assertArrayEquals(originalData, downloadedData);

        // Download via another node
        byte[] downloadedDataNode1 = cluster.node(1).api().getProcessManager().downloadArtifact(sha256);
        assertArrayEquals(originalData, downloadedDataNode1);
    }
}

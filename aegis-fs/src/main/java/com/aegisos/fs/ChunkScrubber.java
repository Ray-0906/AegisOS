package com.aegisos.fs;

import com.aegisos.core.identity.NodeId;
import com.aegisos.core.crypto.Hashing;
import com.aegisos.core.util.HexUtil;
import com.aegisos.proto.ChunkRef;
import com.aegisos.proto.FileMetadata;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ChunkScrubber implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChunkScrubber.class);
    
    private final AegisFS fs;
    private final LocalHealthStore localHealth;
    private final NodeId self;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aegis-chunk-scrubber");
                t.setDaemon(true);
                return t;
            });
    
    private int cursor = 0;
    private static final int BATCH_SIZE = 50;

    public ChunkScrubber(AegisFS fs, LocalHealthStore localHealth, NodeId self) {
        this.fs = fs;
        this.localHealth = localHealth;
        this.self = self;
    }

    public void start() {
        // Run every 10 seconds, processing BATCH_SIZE chunks at a time
        scheduler.scheduleWithFixedDelay(this::scrubSafe, 10, 10, TimeUnit.SECONDS);
        log.info("ChunkScrubber started (batchSize={})", BATCH_SIZE);
    }

    private void scrubSafe() {
        try {
            scrubBatch();
        } catch (Exception e) {
            log.warn("ChunkScrubber error: {}", e.toString());
        }
    }

    private void scrubBatch() {
        List<byte[]> myExpectedChunks = new ArrayList<>();
        for (FileMetadata file : fs.fileIndex().all()) {
            for (ChunkRef ref : file.getChunksList()) {
                boolean amReplica = false;
                for (ByteString nodeByteString : ref.getNodeIdsList()) {
                    if (NodeId.of(nodeByteString.toByteArray()).equals(self)) {
                        amReplica = true;
                        break;
                    }
                }
                if (amReplica) {
                    myExpectedChunks.add(ref.getChunkId().toByteArray());
                }
            }
        }

        if (myExpectedChunks.isEmpty()) {
            cursor = 0;
            return;
        }

        if (cursor >= myExpectedChunks.size()) {
            cursor = 0;
        }

        int processed = 0;
        while (processed < BATCH_SIZE && cursor < myExpectedChunks.size()) {
            byte[] chunkId = myExpectedChunks.get(cursor);
            verifyChunk(chunkId);
            cursor++;
            processed++;
        }
    }

    private void verifyChunk(byte[] chunkId) {
        String hexId = HexUtil.encode(chunkId);
        
        if (!fs.chunkStore().has(chunkId)) {
            localHealth.reportFailure(hexId, false);
            return;
        }

        byte[] data = fs.chunkStore().get(chunkId);
        if (data == null) {
            localHealth.reportFailure(hexId, false);
            return;
        }

        byte[] actualHash = Hashing.sha256(data);
        if (!Arrays.equals(actualHash, chunkId)) {
            localHealth.reportFailure(hexId, true);
        } else {
            localHealth.reportHealthy(hexId);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}

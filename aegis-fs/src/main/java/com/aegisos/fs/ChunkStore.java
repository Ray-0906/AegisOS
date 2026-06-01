package com.aegisos.fs;

import com.aegisos.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Local content-addressed chunk store on disk (one file per chunk, named by chunk id hex). */
public final class ChunkStore {

    private static final Logger log = LoggerFactory.getLogger(ChunkStore.class);

    private final Path dir;

    public ChunkStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create chunk dir " + dir, e);
        }
    }

    private Path pathFor(byte[] chunkId) {
        return dir.resolve(HexUtil.encode(chunkId));
    }

    public void put(byte[] chunkId, byte[] data) {
        try {
            Path tmp = dir.resolve(HexUtil.encode(chunkId) + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, pathFor(chunkId), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed storing chunk", e);
        }
    }

    public boolean has(byte[] chunkId) {
        return Files.exists(pathFor(chunkId));
    }

    public byte[] get(byte[] chunkId) {
        try {
            Path p = pathFor(chunkId);
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading chunk", e);
        }
    }

    public void delete(byte[] chunkId) {
        try {
            Files.deleteIfExists(pathFor(chunkId));
        } catch (IOException e) {
            log.warn("failed deleting chunk: {}", e.getMessage());
        }
    }

    /** Hex ids of all locally-stored chunks. */
    public List<String> listChunkIds() {
        List<String> ids = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> !name.endsWith(".tmp"))
                    .forEach(ids::add);
        } catch (IOException e) {
            log.warn("failed listing chunks: {}", e.getMessage());
        }
        return ids;
    }
}

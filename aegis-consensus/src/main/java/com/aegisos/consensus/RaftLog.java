package com.aegisos.consensus;

import com.aegisos.proto.RaftLogEntry;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Replicated log (design section 3.4). 1-indexed; index 0 is an implicit sentinel
 * {@code (term 0, index 0)}.
 *
 * <p>Persistence (resolved open question Q4) is an append-only file: each entry is
 * length-prefixed. Conflicting suffixes are removed by truncating and rewriting the file
 * (rare in steady state). RocksDB-backed storage is a documented future option.
 *
 * <p>Sprint 6: Supports log truncation via {@link #truncatePrefix} after snapshot
 * creation. A {@code snapshotIndex}/{@code snapshotTerm} offset tracks the truncation
 * point so all index-based lookups remain correct.
 *
 * <p>Thread safety: guarded by a {@link ReentrantLock} rather than {@code synchronized}.
 * In Java 21, {@code synchronized} blocks pin virtual threads to their carrier threads;
 * because this class performs blocking file I/O while holding the lock, synchronized
 * sections starved the virtual thread scheduler under load. {@code ReentrantLock} does
 * not pin carrier threads.
 */
public final class RaftLog {

    private static final Logger log = LoggerFactory.getLogger(RaftLog.class);

    private final Path file;
    private final Path snapshotMetaFile;
    private final List<RaftLogEntry> entries = new ArrayList<>(); // entries.get(i) has index snapshotIndex + i + 1
    private final ReentrantLock lock = new ReentrantLock();
    private DataOutputStream appendStream;

    private long snapshotIndex = 0;  // last index included in the snapshot
    private long snapshotTerm = 0;   // term of the entry at snapshotIndex

    public RaftLog(Path file) {
        this.file = file;
        this.snapshotMetaFile = file.resolveSibling("snapshot-meta.bin");
        load();
    }

    private void load() {
        lock.lock();
        try {
            Files.createDirectories(file.getParent());
            loadSnapshotMeta();
            if (Files.exists(file)) {
                try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
                    while (true) {
                        int len;
                        try {
                            len = in.readInt();
                        } catch (EOFException eof) {
                            break;
                        }
                        byte[] buf = new byte[len];
                        in.readFully(buf);
                        entries.add(RaftLogEntry.parseFrom(buf));
                    }
                }
            }
            openAppend();
            log.info("Loaded Raft log: {} entries (snapshotIndex={}, lastIndex={}, lastTerm={})",
                    entries.size(), snapshotIndex, lastIndex(), lastTerm());
        } catch (IOException e) {
            throw new IllegalStateException("failed to load raft log " + file, e);
        } finally {
            lock.unlock();
        }
    }

    private void openAppend() throws IOException {
        OutputStream os = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        appendStream = new DataOutputStream(new BufferedOutputStream(os));
    }

    // --- snapshot metadata ---

    public long snapshotIndex() {
        lock.lock();
        try {
            return snapshotIndex;
        } finally {
            lock.unlock();
        }
    }

    public long snapshotTerm() {
        lock.lock();
        try {
            return snapshotTerm;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the first index available in the in-memory log (snapshotIndex + 1, or 1 if no snapshot). */
    public long startIndex() {
        lock.lock();
        try {
            return snapshotIndex + 1;
        } finally {
            lock.unlock();
        }
    }

    // --- index/term queries ---

    public long lastIndex() {
        lock.lock();
        try {
            return snapshotIndex + entries.size();
        } finally {
            lock.unlock();
        }
    }

    public long lastTerm() {
        lock.lock();
        try {
            return entries.isEmpty() ? snapshotTerm : entries.get(entries.size() - 1).getTerm();
        } finally {
            lock.unlock();
        }
    }

    public long termAt(long index) {
        lock.lock();
        try {
            if (index <= 0 || index > lastIndex()) {
                return 0;
            }
            if (index == snapshotIndex) {
                return snapshotTerm;
            }
            if (index < startIndex()) {
                return 0; // entry was truncated
            }
            int arrayIndex = (int) (index - snapshotIndex - 1);
            return entries.get(arrayIndex).getTerm();
        } finally {
            lock.unlock();
        }
    }

    public RaftLogEntry get(long index) {
        lock.lock();
        try {
            if (index <= 0 || index > lastIndex() || index < startIndex()) {
                return null;
            }
            int arrayIndex = (int) (index - snapshotIndex - 1);
            return entries.get(arrayIndex);
        } finally {
            lock.unlock();
        }
    }

    public List<RaftLogEntry> entriesFrom(long fromIndex) {
        lock.lock();
        try {
            List<RaftLogEntry> out = new ArrayList<>();
            long start = Math.max(fromIndex, startIndex());
            for (long i = start; i <= lastIndex(); i++) {
                int arrayIndex = (int) (i - snapshotIndex - 1);
                out.add(entries.get(arrayIndex));
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** Appends a new command at the next index for the given term. */
    public RaftLogEntry append(long term, byte[] command) {
        lock.lock();
        try {
            RaftLogEntry entry = RaftLogEntry.newBuilder()
                    .setTerm(term)
                    .setIndex(snapshotIndex + entries.size() + 1)
                    .setCommand(ByteString.copyFrom(command))
                    .build();
            entries.add(entry);
            persistAppend(entry);
            return entry;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Follower-side append from a leader: resolves conflicts by truncating any divergent
     * suffix, then appends the new entries. Returns the new last index.
     */
    public long appendFromLeader(long prevLogIndex, List<RaftLogEntry> incoming) {
        lock.lock();
        try {
            int initialSize = entries.size();
            long index = prevLogIndex;
            boolean rewrite = false;
            boolean appended = false;
            for (RaftLogEntry in : incoming) {
                index++;
                RaftLogEntry existing = get(index);
                if (existing == null) {
                    entries.add(in);
                    appended = true;
                } else if (existing.getTerm() != in.getTerm()) {
                    // conflict: drop this and everything after, then append
                    while (lastIndex() >= index) {
                        entries.remove(entries.size() - 1);
                    }
                    entries.add(in);
                    rewrite = true;
                }
                // else: identical entry already present, skip
            }
            if (rewrite) {
                rewriteFile(); // truncation path: rewrite the whole file
            } else if (appended) {
                // Append only the genuinely-new tail entries to the on-disk log.
                for (int i = initialSize; i < entries.size(); i++) {
                    persistAppend(entries.get(i));
                }
            }
            return lastIndex();
        } finally {
            lock.unlock();
        }
    }

    // --- snapshot truncation ---

    /**
     * Discards all log entries up to and including {@code lastIncludedIndex}.
     * Used after a snapshot has been taken at that index.
     */
    public void truncatePrefix(long lastIncludedIndex, long lastIncludedTerm) {
        lock.lock();
        try {
            if (lastIncludedIndex <= snapshotIndex) {
                return; // already truncated past this point
            }
            int entriesToRemove = (int) (lastIncludedIndex - snapshotIndex);
            if (entriesToRemove > entries.size()) {
                entries.clear();
            } else {
                entries.subList(0, entriesToRemove).clear();
            }
            this.snapshotIndex = lastIncludedIndex;
            this.snapshotTerm = lastIncludedTerm;
            rewriteFile();
            saveSnapshotMeta();
            log.info("Truncated log prefix: snapshotIndex={}, snapshotTerm={}, remaining entries={}",
                    snapshotIndex, snapshotTerm, entries.size());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called when an InstallSnapshot is received: discard the entire log and
     * reset to the snapshot point.
     */
    public void installSnapshot(long lastIncludedIndex, long lastIncludedTerm) {
        lock.lock();
        try {
            entries.clear();
            this.snapshotIndex = lastIncludedIndex;
            this.snapshotTerm = lastIncludedTerm;
            rewriteFile();
            saveSnapshotMeta();
            log.info("Installed snapshot: snapshotIndex={}, snapshotTerm={}",
                    snapshotIndex, snapshotTerm);
        } finally {
            lock.unlock();
        }
    }

    // --- persistence ---

    private void persistAppend(RaftLogEntry entry) {
        try {
            byte[] bytes = entry.toByteArray();
            appendStream.writeInt(bytes.length);
            appendStream.write(bytes);
            appendStream.flush();
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist log entry", e);
        }
    }

    private void rewriteFile() {
        try {
            if (appendStream != null) {
                appendStream.close();
            }
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))) {
                for (RaftLogEntry e : entries) {
                    byte[] bytes = e.toByteArray();
                    out.writeInt(bytes.length);
                    out.write(bytes);
                }
            }
            openAppend();
        } catch (IOException e) {
            throw new IllegalStateException("failed to rewrite raft log", e);
        }
    }

    private void saveSnapshotMeta() {
        try (DataOutputStream out = new DataOutputStream(
                Files.newOutputStream(snapshotMetaFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            out.writeLong(snapshotIndex);
            out.writeLong(snapshotTerm);
        } catch (IOException e) {
            throw new IllegalStateException("failed to save snapshot metadata", e);
        }
    }

    private void loadSnapshotMeta() {
        if (Files.exists(snapshotMetaFile)) {
            try (DataInputStream in = new DataInputStream(Files.newInputStream(snapshotMetaFile))) {
                snapshotIndex = in.readLong();
                snapshotTerm = in.readLong();
                log.info("Loaded snapshot metadata: snapshotIndex={}, snapshotTerm={}", snapshotIndex, snapshotTerm);
            } catch (IOException e) {
                log.warn("Failed to load snapshot metadata, assuming no snapshot: {}", e.toString());
            }
        }
    }

    // --- queries ---

    /** @return true if {@code (lastLogIndex,lastLogTerm)} is at least as up-to-date as ours. */
    public boolean isUpToDate(long candidateLastIndex, long candidateLastTerm) {
        lock.lock();
        try {
            long myTerm = lastTerm();
            long myIndex = lastIndex();
            if (candidateLastTerm != myTerm) {
                return candidateLastTerm > myTerm;
            }
            return candidateLastIndex >= myIndex;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the estimated total serialized size of all log entries in bytes. */
    public long logSizeEstimateBytes() {
        lock.lock();
        try {
            long total = 0;
            for (RaftLogEntry entry : entries) {
                total += entry.getSerializedSize();
            }
            return total;
        } finally {
            lock.unlock();
        }
    }

    /** Returns the on-disk log file size in bytes, or -1 if unavailable. */
    public long diskSizeBytes() {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return -1;
        }
    }

    /** Returns the number of entries in the log. */
    public int entryCount() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }
}

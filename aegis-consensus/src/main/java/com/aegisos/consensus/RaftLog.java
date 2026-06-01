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

/**
 * Replicated log (design section 3.4). 1-indexed; index 0 is an implicit sentinel
 * {@code (term 0, index 0)}.
 *
 * <p>Persistence (resolved open question Q4) is an append-only file: each entry is
 * length-prefixed. Conflicting suffixes are removed by truncating and rewriting the file
 * (rare in steady state). RocksDB-backed storage is a documented future option.
 */
public final class RaftLog {

    private static final Logger log = LoggerFactory.getLogger(RaftLog.class);

    private final Path file;
    private final List<RaftLogEntry> entries = new ArrayList<>(); // entries.get(i) has index i+1
    private DataOutputStream appendStream;

    public RaftLog(Path file) {
        this.file = file;
        load();
    }

    private synchronized void load() {
        try {
            Files.createDirectories(file.getParent());
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
            log.info("Loaded Raft log: {} entries (lastIndex={}, lastTerm={})",
                    entries.size(), lastIndex(), lastTerm());
        } catch (IOException e) {
            throw new IllegalStateException("failed to load raft log " + file, e);
        }
    }

    private void openAppend() throws IOException {
        OutputStream os = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        appendStream = new DataOutputStream(new BufferedOutputStream(os));
    }

    public synchronized long lastIndex() {
        return entries.size();
    }

    public synchronized long lastTerm() {
        return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).getTerm();
    }

    public synchronized long termAt(long index) {
        if (index <= 0 || index > entries.size()) {
            return 0;
        }
        return entries.get((int) (index - 1)).getTerm();
    }

    public synchronized RaftLogEntry get(long index) {
        if (index <= 0 || index > entries.size()) {
            return null;
        }
        return entries.get((int) (index - 1));
    }

    public synchronized List<RaftLogEntry> entriesFrom(long fromIndex) {
        List<RaftLogEntry> out = new ArrayList<>();
        for (long i = fromIndex; i <= entries.size(); i++) {
            out.add(entries.get((int) (i - 1)));
        }
        return out;
    }

    /** Appends a new command at the next index for the given term. */
    public synchronized RaftLogEntry append(long term, byte[] command) {
        RaftLogEntry entry = RaftLogEntry.newBuilder()
                .setTerm(term)
                .setIndex(entries.size() + 1)
                .setCommand(ByteString.copyFrom(command))
                .build();
        entries.add(entry);
        persistAppend(entry);
        return entry;
    }

    /**
     * Follower-side append from a leader: resolves conflicts by truncating any divergent
     * suffix, then appends the new entries. Returns the new last index.
     */
    public synchronized long appendFromLeader(long prevLogIndex, List<RaftLogEntry> incoming) {
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
                while (entries.size() >= index) {
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
    }

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

    /** @return true if {@code (lastLogIndex,lastLogTerm)} is at least as up-to-date as ours. */
    public synchronized boolean isUpToDate(long candidateLastIndex, long candidateLastTerm) {
        long myTerm = lastTerm();
        long myIndex = lastIndex();
        if (candidateLastTerm != myTerm) {
            return candidateLastTerm > myTerm;
        }
        return candidateLastIndex >= myIndex;
    }
}

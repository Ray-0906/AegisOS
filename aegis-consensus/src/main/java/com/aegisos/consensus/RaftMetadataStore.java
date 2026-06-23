package com.aegisos.consensus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persists Raft's required durable state: {@code currentTerm} and {@code votedFor}.
 *
 * <p>Thread safety: guarded by a {@link ReentrantLock} rather than {@code synchronized}.
 * In Java 21, {@code synchronized} blocks pin virtual threads to their carrier threads;
 * because {@link #persist()} performs blocking file I/O while holding the lock,
 * synchronized sections starved the virtual thread scheduler under load.
 * {@code ReentrantLock} does not pin carrier threads.
 */
public final class RaftMetadataStore {

    private final Path file;
    private final ReentrantLock lock = new ReentrantLock();
    private long currentTerm;
    private String votedFor; // node id hex, or null

    public RaftMetadataStore(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        try {
            if (Files.exists(file)) {
                Properties props = new Properties();
                try (var in = Files.newInputStream(file)) {
                    props.load(in);
                }
                currentTerm = Long.parseLong(props.getProperty("currentTerm", "0"));
                votedFor = props.getProperty("votedFor", "");
                if (votedFor.isEmpty()) {
                    votedFor = null;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to load raft metadata " + file, e);
        }
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Properties props = new Properties();
            props.setProperty("currentTerm", Long.toString(currentTerm));
            props.setProperty("votedFor", votedFor == null ? "" : votedFor);
            try (var out = Files.newOutputStream(file)) {
                props.store(out, "AegisOS Raft durable state");
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist raft metadata", e);
        }
    }

    public long currentTerm() {
        lock.lock();
        try {
            return currentTerm;
        } finally {
            lock.unlock();
        }
    }

    public void setCurrentTerm(long term) {
        lock.lock();
        try {
            this.currentTerm = term;
            this.votedFor = null;
            persist();
        } finally {
            lock.unlock();
        }
    }

    public Optional<String> votedFor() {
        lock.lock();
        try {
            return Optional.ofNullable(votedFor);
        } finally {
            lock.unlock();
        }
    }

    public void setVotedFor(String nodeIdHex) {
        lock.lock();
        try {
            this.votedFor = nodeIdHex;
            persist();
        } finally {
            lock.unlock();
        }
    }
}

package com.aegisos.consensus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/** Persists Raft's required durable state: {@code currentTerm} and {@code votedFor}. */
public final class RaftMetadataStore {

    private final Path file;
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

    private synchronized void persist() {
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

    public synchronized long currentTerm() {
        return currentTerm;
    }

    public synchronized void setCurrentTerm(long term) {
        this.currentTerm = term;
        this.votedFor = null;
        persist();
    }

    public synchronized Optional<String> votedFor() {
        return Optional.ofNullable(votedFor);
    }

    public synchronized void setVotedFor(String nodeIdHex) {
        this.votedFor = nodeIdHex;
        persist();
    }
}

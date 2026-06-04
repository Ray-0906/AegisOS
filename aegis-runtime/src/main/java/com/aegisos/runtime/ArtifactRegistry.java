package com.aegisos.runtime;

import com.aegisos.consensus.ClusterStateMachine;
import com.aegisos.proto.ArtifactRecord;
import com.aegisos.proto.CommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replicated in-memory registry for uploaded artifacts.
 * Populated by Raft state machine application of REGISTER_ARTIFACT commands.
 */
public final class ArtifactRegistry {
    private static final Logger log = LoggerFactory.getLogger(ArtifactRegistry.class);
    private final ConcurrentHashMap<String, ArtifactRecord> artifacts = new ConcurrentHashMap<>();

    /** Called by ClusterStateMachine on REGISTER_ARTIFACT commit. */
    public void applyRegister(ArtifactRecord record) {
        log.info("ArtifactRegistry registering: {} ({})", record.getArtifactId(), record.getFileName());
        artifacts.put(record.getArtifactId(), record);
    }

    /** Lookup by SHA-256 hex string. */
    public Optional<ArtifactRecord> bySha256(String sha256) {
        return Optional.ofNullable(artifacts.get(sha256));
    }

    /** List all artifacts. */
    public List<ArtifactRecord> listAll() {
        return List.copyOf(artifacts.values());
    }

    /** Register this applier with the state machine. */
    public void registerWith(ClusterStateMachine sm) {
        sm.register(CommandType.REGISTER_ARTIFACT, (index, cmd) -> {
            try {
                applyRegister(ArtifactRecord.parseFrom(cmd.getPayload()));
            } catch (Exception e) {
                log.error("Failed to parse ArtifactRecord at index {}", index, e);
            }
        });
    }
}

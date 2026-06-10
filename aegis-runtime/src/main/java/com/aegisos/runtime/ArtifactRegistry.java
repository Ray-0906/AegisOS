package com.aegisos.runtime;

import com.aegisos.consensus.SnapshotException;
import com.aegisos.consensus.SnapshotParticipant;
import com.aegisos.consensus.ClusterStateMachine;
import com.aegisos.proto.ArtifactRecord;
import com.aegisos.proto.CommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replicated in-memory registry for uploaded artifacts.
 * Populated by Raft state machine application of REGISTER_ARTIFACT commands.
 */
public final class ArtifactRegistry implements SnapshotParticipant {
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

    /** Check if artifact exists by SHA-256. */
    public boolean exists(String sha256) {
        return artifacts.containsKey(sha256);
    }

    /** List all artifacts. */
    public List<ArtifactRecord> listAll() {
        return List.copyOf(artifacts.values());
    }

    /** Register this applier with the state machine. */
    public void registerWith(ClusterStateMachine sm) {
        sm.register(CommandType.REGISTER_ARTIFACT, (index, cmd) -> {
            try {
                com.aegisos.proto.RegisterArtifact regCmd = com.aegisos.proto.RegisterArtifact.parseFrom(cmd.getPayload());
                applyRegister(regCmd.getArtifact());
            } catch (Exception e) {
                log.error("Failed to parse ArtifactRecord at index {}", index, e);
            }
        });
    }

    // --- SnapshotParticipant ---

    @Override public String id() { return "artifact-registry"; }

    @Override
    public synchronized byte[] snapshot() throws SnapshotException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            var values = List.copyOf(artifacts.values());
            out.writeInt(values.size());
            for (ArtifactRecord r : values) {
                byte[] bytes = r.toByteArray();
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new SnapshotException("Failed to snapshot ArtifactRegistry", e);
        }
    }

    @Override
    public synchronized void restore(byte[] data) throws SnapshotException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            artifacts.clear();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int len = in.readInt();
                byte[] buf = new byte[len];
                in.readFully(buf);
                ArtifactRecord r = ArtifactRecord.parseFrom(buf);
                artifacts.put(r.getArtifactId(), r);
            }
            log.info("Restored ArtifactRegistry: {} artifacts", artifacts.size());
        } catch (IOException e) {
            throw new SnapshotException("Failed to restore ArtifactRegistry", e);
        }
    }
}

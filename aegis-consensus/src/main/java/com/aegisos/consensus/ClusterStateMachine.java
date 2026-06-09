package com.aegisos.consensus;

import com.aegisos.proto.CommandType;
import com.aegisos.proto.KvPut;
import com.aegisos.proto.SnapshotComponent;
import com.aegisos.proto.SnapshotFile;
import com.aegisos.proto.StateCommand;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.zip.CRC32;

/**
 * The AegisOS replicated state machine. Decodes committed {@link StateCommand}s and
 * dispatches each to a registered applier (file metadata, job records, cluster
 * membership changes). Includes a built-in key/value map used for Phase 3 testing.
 *
 * <p>Sprint 6: Also orchestrates snapshot creation and loading via registered
 * {@link SnapshotParticipant}s.
 */
public final class ClusterStateMachine implements RaftStateMachine {

    private static final Logger log = LoggerFactory.getLogger(ClusterStateMachine.class);
    private static final int SNAPSHOT_VERSION = 1;

    private final Map<CommandType, List<BiConsumer<Long, StateCommand>>> appliers = new ConcurrentHashMap<>();
    private final Map<String, byte[]> kv = new ConcurrentHashMap<>();
    private final List<SnapshotParticipant> participants = new CopyOnWriteArrayList<>();

    /** Internal KV store snapshot participant. */
    private final SnapshotParticipant kvSnapshotParticipant = new SnapshotParticipant() {
        @Override public String id() { return "kv-store"; }

        @Override public byte[] snapshot() throws SnapshotException {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(baos);
                Map<String, byte[]> snapshot = new HashMap<>(kv);
                out.writeInt(snapshot.size());
                for (Map.Entry<String, byte[]> entry : snapshot.entrySet()) {
                    out.writeUTF(entry.getKey());
                    out.writeInt(entry.getValue().length);
                    out.write(entry.getValue());
                }
                out.flush();
                return baos.toByteArray();
            } catch (IOException e) {
                throw new SnapshotException("Failed to snapshot KV store", e);
            }
        }

        @Override public void restore(byte[] data) throws SnapshotException {
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
                kv.clear();
                int count = in.readInt();
                for (int i = 0; i < count; i++) {
                    String key = in.readUTF();
                    int len = in.readInt();
                    byte[] val = new byte[len];
                    in.readFully(val);
                    kv.put(key, val);
                }
            } catch (IOException e) {
                throw new SnapshotException("Failed to restore KV store", e);
            }
        }
    };

    public ClusterStateMachine() {
        // Built-in test KV store.
        register(CommandType.KV_PUT, (index, cmd) -> {
            try {
                KvPut put = KvPut.parseFrom(cmd.getPayload());
                kv.put(put.getKey(), put.getValue().toByteArray());
            } catch (Exception e) {
                log.warn("bad KV_PUT at index {}", index);
            }
        });
    }

    public void register(CommandType type, BiConsumer<Long, StateCommand> applier) {
        appliers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(applier);
    }

    /** Registers a snapshot participant for inclusion in snapshot creation and loading. */
    public void registerSnapshotParticipant(SnapshotParticipant participant) {
        participants.add(participant);
        log.info("Registered snapshot participant: {}", participant.id());
    }

    /** Returns all registered snapshot participants (including the internal KV store). */
    public List<SnapshotParticipant> snapshotParticipants() {
        List<SnapshotParticipant> all = new ArrayList<>(participants);
        all.add(kvSnapshotParticipant);
        return Collections.unmodifiableList(all);
    }

    @Override
    public void apply(long index, byte[] command) {
        try {
            StateCommand cmd = StateCommand.parseFrom(command);
            log.info("ClusterStateMachine applying {} at {}", cmd.getType(), index);
            if (cmd.getType() == CommandType.UPDATE_JOB) {
                try {
                    com.aegisos.proto.JobUpdate update = com.aegisos.proto.JobUpdate.parseFrom(cmd.getPayload());
                    log.info("Applying UPDATE_JOB for {}: state={}", update.getJobId(), update.getState());
                } catch (Exception e) {}
            }
            List<BiConsumer<Long, StateCommand>> list = appliers.get(cmd.getType());
            if (list != null && !list.isEmpty()) {
                for (BiConsumer<Long, StateCommand> applier : list) {
                    applier.accept(index, cmd);
                }
            } else {
                log.debug("No applier for command {} at index {}", cmd.getType(), index);
            }
        } catch (Exception e) {
            log.error("Failed to decode committed command at index {}: {}", index, e.toString());
        }
    }

    /**
     * Creates a snapshot of all registered participants' state.
     *
     * @param lastIncludedIndex the commit index at the time of the snapshot
     * @param lastIncludedTerm  the term of the entry at lastIncludedIndex
     * @return the serialized {@link SnapshotFile} bytes
     */
    public byte[] takeSnapshot(long lastIncludedIndex, long lastIncludedTerm) throws SnapshotException {
        List<SnapshotComponent> components = new ArrayList<>();
        for (SnapshotParticipant p : snapshotParticipants()) {
            byte[] data = p.snapshot();
            components.add(SnapshotComponent.newBuilder()
                    .setId(p.id())
                    .setData(ByteString.copyFrom(data))
                    .build());
            log.info("Snapshot participant '{}' serialized {} bytes", p.id(), data.length);
        }

        // Compute checksum over all component data
        CRC32 crc = new CRC32();
        for (SnapshotComponent c : components) {
            crc.update(c.getData().toByteArray());
        }

        SnapshotFile snapshotFile = SnapshotFile.newBuilder()
                .setLastIncludedIndex(lastIncludedIndex)
                .setLastIncludedTerm(lastIncludedTerm)
                .setSnapshotCreatedAt(System.currentTimeMillis())
                .setSnapshotVersion(SNAPSHOT_VERSION)
                .setChecksum((int) crc.getValue())
                .addAllComponents(components)
                .build();

        byte[] result = snapshotFile.toByteArray();
        log.info("Snapshot taken: index={}, term={}, components={}, size={} bytes",
                lastIncludedIndex, lastIncludedTerm, components.size(), result.length);
        return result;
    }

    /**
     * Loads a snapshot, restoring all participants' state from the serialized data.
     *
     * @param data the serialized {@link SnapshotFile} bytes
     * @return the parsed SnapshotFile (for metadata extraction)
     */
    public SnapshotFile loadSnapshot(byte[] data) throws SnapshotException {
        try {
            SnapshotFile snapshotFile = SnapshotFile.parseFrom(data);

            // Validate version
            if (snapshotFile.getSnapshotVersion() > SNAPSHOT_VERSION) {
                throw new SnapshotException("Unsupported snapshot version: " + snapshotFile.getSnapshotVersion()
                        + " (max supported: " + SNAPSHOT_VERSION + ")");
            }

            // Validate checksum
            CRC32 crc = new CRC32();
            for (SnapshotComponent c : snapshotFile.getComponentsList()) {
                crc.update(c.getData().toByteArray());
            }
            if ((int) crc.getValue() != snapshotFile.getChecksum()) {
                throw new SnapshotException("Snapshot checksum mismatch: expected " + snapshotFile.getChecksum()
                        + ", computed " + (int) crc.getValue());
            }

            // Build participant lookup
            Map<String, SnapshotParticipant> byId = new HashMap<>();
            for (SnapshotParticipant p : snapshotParticipants()) {
                byId.put(p.id(), p);
            }

            // Dispatch each component to its participant
            for (SnapshotComponent component : snapshotFile.getComponentsList()) {
                SnapshotParticipant participant = byId.get(component.getId());
                if (participant != null) {
                    participant.restore(component.getData().toByteArray());
                    log.info("Restored snapshot participant '{}' ({} bytes)",
                            component.getId(), component.getData().size());
                } else {
                    log.warn("Unknown snapshot component '{}' — skipping (forward compatibility)",
                            component.getId());
                }
            }

            log.info("Snapshot loaded: index={}, term={}, version={}, components={}",
                    snapshotFile.getLastIncludedIndex(), snapshotFile.getLastIncludedTerm(),
                    snapshotFile.getSnapshotVersion(), snapshotFile.getComponentsCount());

            return snapshotFile;
        } catch (SnapshotException e) {
            throw e;
        } catch (Exception e) {
            throw new SnapshotException("Failed to parse snapshot", e);
        }
    }

    public Optional<byte[]> kvGet(String key) {
        return Optional.ofNullable(kv.get(key));
    }
}

package com.aegisos.consensus;

import com.aegisos.proto.CommandType;
import com.aegisos.proto.KvPut;
import com.aegisos.proto.StateCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * The AegisOS replicated state machine. Decodes committed {@link StateCommand}s and
 * dispatches each to a registered applier (file metadata, job records, cluster
 * membership changes). Includes a built-in key/value map used for Phase 3 testing.
 */
public final class ClusterStateMachine implements RaftStateMachine {

    private static final Logger log = LoggerFactory.getLogger(ClusterStateMachine.class);

    private final Map<CommandType, java.util.List<BiConsumer<Long, StateCommand>>> appliers = new ConcurrentHashMap<>();
    private final Map<String, byte[]> kv = new ConcurrentHashMap<>();

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
        appliers.computeIfAbsent(type, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(applier);
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
            java.util.List<BiConsumer<Long, StateCommand>> list = appliers.get(cmd.getType());
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

    public Optional<byte[]> kvGet(String key) {
        return Optional.ofNullable(kv.get(key));
    }
}

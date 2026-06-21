package com.aegisos.fs.audit;

import com.aegisos.consensus.SnapshotException;
import com.aegisos.consensus.SnapshotParticipant;
import com.aegisos.proto.RepairChunk;
import com.aegisos.proto.RepairComplete;
import com.aegisos.core.util.HexUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RepairTaskStore implements SnapshotParticipant {

    private static final Logger log = LoggerFactory.getLogger(RepairTaskStore.class);

    public enum TaskStatus { PENDING, COMPLETE, EXPIRED }

    public static final class RepairTask {
        private final String repairId;
        private final String chunkIdHex;
        private final List<Long> evidenceScans;
        private final long verifiedAt;
        private long committedAt;
        private TaskStatus status;

        public RepairTask(String repairId, String chunkIdHex, List<Long> evidenceScans, long verifiedAt, long committedAt, TaskStatus status) {
            this.repairId = repairId;
            this.chunkIdHex = chunkIdHex;
            this.evidenceScans = new ArrayList<>(evidenceScans);
            this.verifiedAt = verifiedAt;
            this.committedAt = committedAt;
            this.status = status;
        }

        public String repairId() { return repairId; }
        public String chunkIdHex() { return chunkIdHex; }
        public List<Long> evidenceScans() { return Collections.unmodifiableList(evidenceScans); }
        public long verifiedAt() { return verifiedAt; }
        public long committedAt() { return committedAt; }
        public TaskStatus status() { return status; }
        public void setStatus(TaskStatus status) { this.status = status; }
        public void setCommittedAt(long committedAt) { this.committedAt = committedAt; }

        @Override
        public String toString() {
            return "RepairTask{" +
                    "repairId='" + repairId + '\'' +
                    ", chunkIdHex='" + chunkIdHex + '\'' +
                    ", evidenceScans=" + evidenceScans +
                    ", verifiedAt=" + verifiedAt +
                    ", committedAt=" + committedAt +
                    ", status=" + status +
                    '}';
        }
    }

    private final Map<String, RepairTask> tasks = new LinkedHashMap<>(); // repairId -> RepairTask

    /** Applied when REPAIR_CHUNK is committed. */
    public synchronized void applyRepairChunk(long index, RepairChunk cmd) {
        String repairId = cmd.getRepairId();
        String chunkIdHex = HexUtil.encode(cmd.getChunkId().toByteArray());
        RepairTask task = new RepairTask(
            repairId,
            chunkIdHex,
            cmd.getEvidenceScansList(),
            cmd.getVerifiedAt(),
            System.currentTimeMillis(), // committedAt
            TaskStatus.PENDING
        );
        tasks.put(repairId, task);
        log.debug("Repair task created: {}", repairId);
    }

    /** Applied when REPAIR_COMPLETE is committed. */
    public synchronized void applyRepairComplete(long index, RepairComplete cmd) {
        RepairTask task = tasks.get(cmd.getRepairId());
        if (task != null && task.status() == TaskStatus.PENDING) {
            task.setStatus(TaskStatus.COMPLETE);
        }
    }

    /** Returns true if there's a PENDING task for this chunk. */
    public synchronized boolean hasPendingRepair(String chunkIdHex) {
        for (RepairTask task : tasks.values()) {
            if (task.chunkIdHex().equalsIgnoreCase(chunkIdHex) && task.status() == TaskStatus.PENDING) {
                return true;
            }
        }
        return false;
    }

    /** Returns the PENDING task for a chunk, if any. */
    public synchronized Optional<RepairTask> pendingFor(String chunkIdHex) {
        for (RepairTask task : tasks.values()) {
            if (task.chunkIdHex().equalsIgnoreCase(chunkIdHex) && task.status() == TaskStatus.PENDING) {
                return Optional.of(task);
            }
        }
        return Optional.empty();
    }

    /** All current tasks (for REST API). */
    public synchronized List<RepairTask> all() {
        return new ArrayList<>(tasks.values());
    }

    /** Leader cleanup: expire tasks older than timeout. */
    public synchronized List<RepairTask> expireStaleTasks(long maxAgeMs) {
        long now = System.currentTimeMillis();
        List<RepairTask> expired = new ArrayList<>();
        for (RepairTask task : tasks.values()) {
            if (task.status() == TaskStatus.PENDING && (now - task.committedAt() > maxAgeMs)) {
                task.setStatus(TaskStatus.EXPIRED);
                expired.add(task);
            }
        }
        return expired;
    }

    /** Returns the PENDING task matching a repairId, if any. */
    public synchronized Optional<RepairTask> pendingByRepairId(String repairId) {
        RepairTask task = tasks.get(repairId);
        if (task != null && task.status() == TaskStatus.PENDING) {
            return Optional.of(task);
        }
        return Optional.empty();
    }

    // --- SnapshotParticipant ---

    @Override public String id() { return "repair-task-store"; }

    @Override
    public synchronized byte[] snapshot() throws SnapshotException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);
            out.writeInt(tasks.size());
            for (RepairTask task : tasks.values()) {
                out.writeUTF(task.repairId());
                out.writeUTF(task.chunkIdHex());
                List<Long> scans = task.evidenceScans();
                out.writeInt(scans.size());
                for (long s : scans) {
                    out.writeLong(s);
                }
                out.writeLong(task.verifiedAt());
                out.writeLong(task.committedAt());
                out.writeInt(task.status().ordinal());
            }
            out.flush();
            log.debug("Persisted {} repair tasks", tasks.size());
            return baos.toByteArray();
        } catch (IOException e) {
            throw new SnapshotException("Failed to snapshot RepairTaskStore", e);
        }
    }

    @Override
    public synchronized void restore(byte[] data) throws SnapshotException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            tasks.clear();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String repairId = in.readUTF();
                String chunkIdHex = in.readUTF();
                int scanCount = in.readInt();
                List<Long> scans = new ArrayList<>();
                for (int j = 0; j < scanCount; j++) {
                    scans.add(in.readLong());
                }
                long verifiedAt = in.readLong();
                long committedAt = in.readLong();
                TaskStatus status = TaskStatus.values()[in.readInt()];
                tasks.put(repairId, new RepairTask(repairId, chunkIdHex, scans, verifiedAt, committedAt, status));
            }
            log.debug("Restored {} repair tasks", count);
        } catch (IOException e) {
            throw new SnapshotException("Failed to restore RepairTaskStore", e);
        }
    }
}

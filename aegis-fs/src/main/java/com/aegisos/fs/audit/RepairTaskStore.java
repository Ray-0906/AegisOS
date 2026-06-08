package com.aegisos.fs.audit;

import com.aegisos.proto.RepairChunk;
import com.aegisos.proto.RepairComplete;
import com.aegisos.core.util.HexUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RepairTaskStore {

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
}

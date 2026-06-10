package com.aegisos.fs.audit;

public final class RepairOutcome {
    public enum Status {
        REPAIR_PROPOSED,    // Phase A: REPAIR_CHUNK submitted
        COPY_SUCCEEDED,     // Phase B: physical copy done, REPAIR_COMPLETE submitted
        COPY_FAILED,        // Phase B: physical copy failed, staying PENDING
        STALE,              // Recommendation too old
        NO_LONGER_NEEDED,   // Re-verification shows healed
        NO_SOURCE,          // No ALIVE node holds the chunk
        NO_TARGET,          // No valid target node available
        BLOCKED,            // PENDING RepairTask already exists for this chunk
        PROPOSAL_FAILED     // Raft proposal rejected
    }

    private final String chunkId;
    private final Status status;
    private final String details;
    private final String repairId;  // null if not proposed

    public RepairOutcome(String chunkId, Status status, String details, String repairId) {
        this.chunkId = chunkId;
        this.status = status;
        this.details = details;
        this.repairId = repairId;
    }

    public String chunkId() { return chunkId; }
    public Status status() { return status; }
    public String details() { return details; }
    public String repairId() { return repairId; }

    @Override
    public String toString() {
        return "RepairOutcome{" +
                "chunkId='" + chunkId + '\'' +
                ", status=" + status +
                ", details='" + details + '\'' +
                ", repairId='" + repairId + '\'' +
                '}';
    }
}

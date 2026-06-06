package com.aegisos.fs;

/**
 * Represents the local operational health state of a physical chunk.
 * This is an ephemeral state, deliberately NOT stored in the Raft state machine.
 */
public enum ReplicaState {
    /** The health is not yet known. */
    UNKNOWN,

    /** The chunk has been verified to exist and match its hash. */
    HEALTHY,

    /** The chunk failed a local read or hash check, but hasn't failed enough times to be deemed missing/corrupt. */
    SUSPECT,

    /** The chunk file is conclusively missing from disk. */
    MISSING,

    /** The chunk file is conclusively present but its hash does not match its ID. */
    CORRUPT,

    /** The chunk file is orphaned or corrupt and has been moved to the quarantine directory. */
    QUARANTINED
}

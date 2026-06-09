package com.aegisos.consensus;

/**
 * A component of the replicated state machine that can serialize and restore
 * its state for Raft snapshotting. Each participant owns a distinct slice of
 * the state machine (e.g. file index, job registry, repair task store).
 *
 * <p>The {@link ClusterStateMachine} orchestrates snapshot creation by collecting
 * bytes from every registered participant, and snapshot loading by dispatching
 * bytes to the participant matching each component's {@link #id()}.
 */
public interface SnapshotParticipant {

    /** Stable identifier for this component (e.g. "file-index"). Must be unique across participants. */
    String id();

    /** Serialize current state to bytes. Called while the state machine is quiesced. */
    byte[] snapshot() throws SnapshotException;

    /** Replace current state from bytes. Called during snapshot load or InstallSnapshot. */
    void restore(byte[] data) throws SnapshotException;
}

package com.aegisos.consensus;

/**
 * Applies committed log entries in index order. Implementations decode the opaque
 * command bytes (an AegisOS {@code StateCommand}) and mutate their own state.
 */
public interface RaftStateMachine {
    void apply(long index, byte[] command);
    byte[] takeSnapshot();
    void installSnapshot(byte[] data);
}

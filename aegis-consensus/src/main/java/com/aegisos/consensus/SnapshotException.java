package com.aegisos.consensus;

/**
 * Thrown when a snapshot participant fails to serialize or restore its state.
 * Distinguishes snapshot-specific failures from generic RuntimeExceptions,
 * enabling the snapshot loader to log context and decide on recovery strategy.
 */
public class SnapshotException extends Exception {

    public SnapshotException(String message) {
        super(message);
    }

    public SnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}

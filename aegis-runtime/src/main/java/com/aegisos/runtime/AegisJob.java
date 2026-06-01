package com.aegisos.runtime;

import java.io.Serializable;

/**
 * A user-defined unit of work executed somewhere in the cluster (design section 3.7).
 *
 * <p>Implementations must be {@link Serializable} so the whole job object can be shipped
 * to the assigned node. For v0.1 the job class must be present on the target node's
 * classpath; shipping bytecode through AegisFS is the designed future delivery mechanism.
 *
 * @param <T> the (serializable) result type
 */
public interface AegisJob<T extends Serializable> extends Serializable {

    T execute(JobContext ctx) throws Exception;

    /** Optional checkpoint hook (Phase 6). Return null if the job is not checkpointable. */
    default Serializable captureState() {
        return null;
    }

    /** Optional restore hook (Phase 6), called before {@link #execute} on a resumed job. */
    default void restoreState(Serializable state) {
    }
}

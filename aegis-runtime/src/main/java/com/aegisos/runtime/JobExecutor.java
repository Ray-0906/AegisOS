package com.aegisos.runtime;

import com.aegisos.core.identity.NodeId;
import com.aegisos.fs.AegisFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

/**
 * Runs a single {@link AegisJob} on a virtual thread, applying any restore state first.
 * Lifecycle bookkeeping and Raft updates are handled by {@link ProcessRuntimeAgent}.
 */
public final class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final NodeId self;
    private final AegisFS fileSystem;

    public JobExecutor(NodeId self, AegisFS fileSystem) {
        this.self = self;
        this.fileSystem = fileSystem;
    }

    /** Deserializes, optionally restores, and runs the job, returning the serialized result. */
    public byte[] run(String jobId, byte[] jobBytes, byte[] restoreState) throws Exception {
        AegisJob<?> job = Serialization.deserialize(jobBytes);
        if (job == null) {
            throw new IllegalArgumentException("job payload is empty");
        }
        if (restoreState != null && restoreState.length > 0) {
            Serializable state = Serialization.deserialize(restoreState);
            job.restoreState(state);
            log.info("Restored job {} from checkpoint", jobId);
        }
        JobContext ctx = new JobContext(jobId, self, fileSystem);
        Serializable result = job.execute(ctx);
        return Serialization.serialize(result);
    }

    /** Captures the current checkpoint state of a (already-deserialized) job, or null. */
    public byte[] captureState(AegisJob<?> job) {
        Serializable state = job.captureState();
        return state == null ? null : Serialization.serialize(state);
    }
}

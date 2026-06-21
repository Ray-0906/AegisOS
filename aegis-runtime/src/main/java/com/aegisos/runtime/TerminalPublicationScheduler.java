package com.aegisos.runtime;

import com.aegisos.consensus.ConsensusModule;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.JobState;
import com.aegisos.proto.JobUpdate;
import com.aegisos.proto.StateCommand;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

public class TerminalPublicationScheduler {
    private static final Logger log = LoggerFactory.getLogger(TerminalPublicationScheduler.class);
    
    private final ConsensusModule consensus;
    private final DelayQueue<TerminalPublicationTask> queue = new DelayQueue<>();
    private final Thread workerThread;
    private volatile boolean shuttingDown = false;
    private final BiPredicate<String, Long> isSuperseded;

    public TerminalPublicationScheduler(ConsensusModule consensus, BiPredicate<String, Long> isSuperseded) {
        this.consensus = consensus;
        this.isSuperseded = isSuperseded;
        this.workerThread = Thread.ofVirtual().name("aegis-terminal-pub").start(this::runLoop);
    }

    public void enqueue(String jobId, long executionId, JobState state, byte[] result, String error) {
        if (shuttingDown) return;
        queue.add(new TerminalPublicationTask(jobId, executionId, state, result, error, 1, Instant.now()));
    }

    public void shutdown() {
        shuttingDown = true;
        workerThread.interrupt();
    }

    private void runLoop() {
        while (!shuttingDown) {
            try {
                TerminalPublicationTask task = queue.take();
                if (shuttingDown) break;

                if (isSuperseded.test(task.jobId(), task.executionId())) {
                    log.info("Dropping terminal publication {} for superseded execution {} of job {}", task.state(), task.executionId(), task.jobId());
                    continue; // Drop task
                }

                boolean success = publish(task);
                if (!success) {
                    // Exponential backoff
                    int nextAttempt = task.attempt() + 1;
                    long backoffSeconds = 1L << Math.min(nextAttempt - 1, 5); // 1, 2, 4, 8, 16, 32
                    Instant nextTime = Instant.now().plusSeconds(backoffSeconds);
                    log.debug("Requeueing {} publication for job {} execution {}, next attempt in {}s", task.state(), task.jobId(), task.executionId(), backoffSeconds);
                    queue.add(new TerminalPublicationTask(task.jobId(), task.executionId(), task.state(), task.result(), task.error(), nextAttempt, nextTime));
                } else {
                    log.info("Durably published {} for job {} execution {}", task.state(), task.jobId(), task.executionId());
                }

            } catch (InterruptedException e) {
                if (shuttingDown) break;
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Unexpected error in TerminalPublicationScheduler", e);
            }
        }
    }

    private boolean publish(TerminalPublicationTask task) {
        JobUpdate.Builder b = JobUpdate.newBuilder()
                .setJobId(task.jobId())
                .setExecutionId(task.executionId())
                .setState(task.state());
        if (task.result() != null) b.setResult(ByteString.copyFrom(task.result()));
        if (task.error() != null) b.setError(task.error());

        StateCommand cmd = StateCommand.newBuilder()
                .setType(CommandType.UPDATE_JOB)
                .setPayload(b.build().toByteString())
                .build();

        try {
            // Propose with a relatively short timeout to avoid blocking the scheduler thread too long
            consensus.propose(cmd).get(5, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public record TerminalPublicationTask(
            String jobId,
            long executionId,
            JobState state,
            byte[] result,
            String error,
            int attempt,
            Instant nextAttemptAt
    ) implements Delayed {
        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(java.time.Duration.between(Instant.now(), nextAttemptAt));
        }

        @Override
        public int compareTo(Delayed o) {
            if (o instanceof TerminalPublicationTask other) {
                return this.nextAttemptAt.compareTo(other.nextAttemptAt);
            }
            return Long.compare(this.getDelay(TimeUnit.MILLISECONDS), o.getDelay(TimeUnit.MILLISECONDS));
        }
    }
}

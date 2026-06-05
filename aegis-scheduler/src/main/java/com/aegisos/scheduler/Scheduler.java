package com.aegisos.scheduler;

import com.aegisos.consensus.ConsensusModule;
import com.aegisos.core.identity.NodeId;
import com.aegisos.core.message.AegisMessage;
import com.aegisos.core.message.MessageType;
import com.aegisos.discovery.DiscoveryService;
import com.aegisos.network.NetworkLayer;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.JobRecord;
import com.aegisos.proto.JobSpec;
import com.aegisos.proto.JobState;
import com.aegisos.proto.NodeResources;
import com.aegisos.proto.ProbeRequest;
import com.aegisos.proto.ProbeResult;
import com.aegisos.proto.StateCommand;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Places jobs on the best-fit node (design section 3.6): score all alive nodes, probe the
 * top candidate, and record the assignment in the Raft log. Runs on every node.
 */
public final class Scheduler {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);
    private static final long PROBE_TIMEOUT_MS = 2_000;
    private static final long ASSIGN_TIMEOUT_MS = 30_000;

    private final NetworkLayer network;
    private final DiscoveryService discovery;
    private final ConsensusModule consensus;
    private final NodeResourcesView view;
    private final NodeId self;
    private final PlacementAlgorithm placement = new PlacementAlgorithm();
    private final ResourceAllocator allocator;
    private long schedulerEpoch = 0;

    private volatile BooleanSupplier acceptProbe = () -> true;

    public Scheduler(NetworkLayer network, DiscoveryService discovery, ConsensusModule consensus,
                     NodeResourcesView view, ResourceAllocator allocator, NodeId self) {
        this.network = network;
        this.discovery = discovery;
        this.consensus = consensus;
        this.view = view;
        this.allocator = allocator;
        this.self = self;
    }

    public void registerAppliers() {
        consensus.stateMachine().register(CommandType.ASSIGN_JOB, (index, cmd) -> {
            try {
                JobRecord record = JobRecord.parseFrom(cmd.getPayload());
                if (ByteString.copyFrom(self.toBytes()).equals(record.getAssignedNodeId())) {
                    allocator.commitHardAllocation(record.getSpec().getJobId(), record.getSpec().getResources());
                }
            } catch (Exception e) {
                log.warn("bad ASSIGN_JOB in allocator: {}", e.toString());
            }
        });
        
        consensus.stateMachine().register(CommandType.UPDATE_JOB, (index, cmd) -> {
            try {
                com.aegisos.proto.JobUpdate update = com.aegisos.proto.JobUpdate.parseFrom(cmd.getPayload());
                if (update.getState() == JobState.COMPLETED || update.getState() == JobState.FAILED || update.getState() == JobState.LOST) {
                    allocator.releaseAllocation(update.getJobId());
                }
            } catch (Exception e) {
                log.warn("bad UPDATE_JOB in allocator: {}", e.toString());
            }
        });
    }

    public void start() {
        network.registerHandler(MessageType.PROBE, this::onProbe);
        log.info("Scheduler started");
    }

    public void setAcceptProbe(BooleanSupplier acceptProbe) {
        this.acceptProbe = acceptProbe;
    }

    /**
     * Selects a node for the job, records the assignment in the Raft log, and returns the
     * chosen node. Throws if no node accepts.
     */
    public NodeId schedule(JobSpec spec, long executionId) throws Exception {
        schedulerEpoch++;
        List<NodeId> candidates = rankedCandidates();
        for (NodeId candidate : candidates) {
            if (probe(candidate, spec.getJobId(), spec.getResources())) {
                if (System.getProperty("aegis.test.kill_after_probe") != null) {
                    log.info("TEST HOOK: Killing leader after probe! Reservation leaked on {}", candidate.shortId());
                    System.exit(1);
                }
                JobRecord record = JobRecord.newBuilder()
                        .setSpec(spec)
                        .setAssignedNodeId(ByteString.copyFrom(candidate.toBytes()))
                        .setState(JobState.QUEUED)
                        .setExecutionId(executionId)
                        .build();
                consensus.propose(StateCommand.newBuilder()
                        .setType(CommandType.ASSIGN_JOB)
                        .setPayload(record.toByteString())
                        .build()).get(ASSIGN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                log.info("Scheduled job {} on {}", spec.getJobId(), candidate.shortId());
                return candidate;
            }
        }
        throw new IllegalStateException("no node accepted job " + spec.getJobId());
    }

    private List<NodeId> rankedCandidates() {
        List<NodeId> all = new ArrayList<>(discovery.membership().alivePeerIds());
        all.add(self);
        all.sort(Comparator.comparingDouble(this::scoreOf).reversed());
        return all;
    }

    private double scoreOf(NodeId id) {
        NodeResources r = view.get(id).orElse(NodeResources.getDefaultInstance());
        return placement.score(r);
    }

    private boolean probe(NodeId candidate, String jobId, com.aegisos.proto.ResourceRequest resources) {
        if (candidate.equals(self)) {
            return acceptProbe.getAsBoolean() && allocator.tryReserve(jobId, schedulerEpoch, resources) != null;
        }
        try {
            ProbeRequest req = ProbeRequest.newBuilder().setJobId(jobId).setResources(resources).build();
            AegisMessage reply = network.request(candidate, MessageType.PROBE,
                    req.toByteArray(), PROBE_TIMEOUT_MS).get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            boolean accepted = ProbeResult.parseFrom(reply.payload()).getAccepted();
            log.info("probe to {} returned accepted={}", candidate.shortId(), accepted);
            return accepted;
        } catch (Exception e) {
            log.info("probe to {} failed: {}", candidate.shortId(), e.toString());
            return false;
        }
    }

    private AegisMessage onProbe(AegisMessage msg) {
        try {
            ProbeRequest req = ProbeRequest.parseFrom(msg.payload());
            boolean accept = acceptProbe.getAsBoolean();
            if (accept) {
                String reservationId = allocator.tryReserve(req.getJobId(), 0, req.getResources());
                if (reservationId == null) accept = false;
            }
            ProbeResult result = ProbeResult.newBuilder()
                    .setNodeId(ByteString.copyFrom(self.toBytes()))
                    .setAccepted(accept)
                    .build();
            return new AegisMessage(null, msg.sender(), MessageType.PROBE_RESULT, result.toByteArray());
        } catch (Exception e) {
            log.error("Failed to parse probe", e);
            return null;
        }
    }
}

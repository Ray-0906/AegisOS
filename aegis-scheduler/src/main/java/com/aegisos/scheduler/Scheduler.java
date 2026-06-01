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

    private volatile BooleanSupplier acceptProbe = () -> true;

    public Scheduler(NetworkLayer network, DiscoveryService discovery, ConsensusModule consensus,
                     NodeResourcesView view, NodeId self) {
        this.network = network;
        this.discovery = discovery;
        this.consensus = consensus;
        this.view = view;
        this.self = self;
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
    public NodeId schedule(JobSpec spec) throws Exception {
        List<NodeId> candidates = rankedCandidates();
        for (NodeId candidate : candidates) {
            if (probe(candidate, spec.getJobId())) {
                JobRecord record = JobRecord.newBuilder()
                        .setSpec(spec)
                        .setAssignedNodeId(ByteString.copyFrom(candidate.toBytes()))
                        .setState(JobState.QUEUED)
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

    private boolean probe(NodeId candidate, String jobId) {
        if (candidate.equals(self)) {
            return acceptProbe.getAsBoolean();
        }
        try {
            ProbeRequest req = ProbeRequest.newBuilder().setJobId(jobId).build();
            AegisMessage reply = network.request(candidate, MessageType.PROBE,
                    req.toByteArray(), PROBE_TIMEOUT_MS).get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return ProbeResult.parseFrom(reply.payload()).getAccepted();
        } catch (Exception e) {
            log.debug("probe to {} failed: {}", candidate.shortId(), e.toString());
            return false;
        }
    }

    private AegisMessage onProbe(AegisMessage msg) {
        boolean accept = acceptProbe.getAsBoolean();
        ProbeResult result = ProbeResult.newBuilder()
                .setNodeId(ByteString.copyFrom(self.toBytes()))
                .setAccepted(accept)
                .build();
        return new AegisMessage(null, msg.sender(), MessageType.PROBE_RESULT, result.toByteArray());
    }
}

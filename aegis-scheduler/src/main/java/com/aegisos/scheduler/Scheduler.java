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
public final class Scheduler implements AutoCloseable {

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
    private final java.util.concurrent.ExecutorService probeExecutor = com.aegisos.core.ExecutorRegistry.register("schedulerProbe", java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    private final java.util.concurrent.atomic.AtomicLong schedulerEpoch = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalDownloadBytesSaved = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicInteger localityWins = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.ConcurrentMap<String, ByteString> activeJobAssignments = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<ByteString, java.util.concurrent.atomic.AtomicInteger> assignmentLoad = new java.util.concurrent.ConcurrentHashMap<>();
    private final Object placementLock = new Object();
    private volatile BooleanSupplier acceptProbe = () -> true;
    private volatile LocalityProvider localityProvider = new LocalityProvider() {
        public long getDownloadBytesSaved(List<String> a, String c) { return 0; }
        public int getRunningJobs() { return 0; }
    };
    private final com.aegisos.core.observability.MetricsRegistry metricsRegistry;

    public Scheduler(NetworkLayer network, DiscoveryService discovery, ConsensusModule consensus,
                     NodeResourcesView view, ResourceAllocator allocator, NodeId self,
                     com.aegisos.core.observability.MetricsRegistry metricsRegistry) {
        this.network = network;
        this.discovery = discovery;
        this.consensus = consensus;
        this.view = view;
        this.allocator = allocator;
        this.self = self;
        this.metricsRegistry = metricsRegistry;

    }

    public long getTotalDownloadBytesSaved() {
        return totalDownloadBytesSaved.get();
    }

    public int getLocalityWins() {
        return localityWins.get();
    }

    public void registerAppliers() {
        consensus.stateMachine().register(CommandType.ASSIGN_JOB, (index, cmd) -> {
            try {
                JobRecord record = JobRecord.parseFrom(cmd.getPayload());
                if (ByteString.copyFrom(self.toBytes()).equals(record.getAssignedNodeId())) {
                    allocator.commitHardAllocation(record.getSpec().getJobId(), record.getSpec().getResources());
                } else {
                    allocator.releaseAllocation(record.getSpec().getJobId());
                }
                trackAssignment(record.getSpec().getJobId(), record.getAssignedNodeId());
            } catch (Exception e) {
                log.warn("bad ASSIGN_JOB in allocator: {}", e.toString());
            }
        });
        
        consensus.stateMachine().register(CommandType.UPDATE_JOB, (index, cmd) -> {
            try {
                com.aegisos.proto.JobUpdate update = com.aegisos.proto.JobUpdate.parseFrom(cmd.getPayload());
                if (update.getState() == JobState.COMPLETED || update.getState() == JobState.FAILED
                        || update.getState() == JobState.LOST || update.getState() == JobState.CANCELLED) {
                    allocator.releaseAllocation(update.getJobId());
                    releaseTrackedAssignment(update.getJobId());
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

    public void setLocalityProvider(LocalityProvider localityProvider) {
        this.localityProvider = localityProvider;
    }

    public ResourceAllocator allocator() {
        return allocator;
    }

    /**
     * Selects a node for the job, records the assignment in the Raft log, and returns the
     * chosen node. Throws if no node accepts.
     */
    public NodeId schedule(JobSpec spec, long executionId, String checkpointFileId) throws Exception {
        long epoch = schedulerEpoch.incrementAndGet();
        List<NodeId> allNodes = new ArrayList<>(discovery.membership().alivePeerIds());
        allNodes.add(self);
        
        List<java.util.concurrent.CompletableFuture<ProbeResult>> futures = new ArrayList<>();
        for (NodeId candidate : allNodes) {
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> probe(candidate, spec, checkpointFileId, epoch), probeExecutor));
        }

        List<ProbeResult> results = new ArrayList<>();
        for (java.util.concurrent.CompletableFuture<ProbeResult> f : futures) {
            try {
                ProbeResult res = f.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (res != null && res.getAccepted()) {
                    results.add(res);
                }
            } catch (Exception e) {
                // Ignore timeouts or failures
            }
        }

        if (results.isEmpty()) {
            throw new IllegalStateException("no node accepted job " + spec.getJobId());
        }

        SchedulerConfig config = new SchedulerConfig();
        long maxBytesSaved = results.stream().mapToLong(ProbeResult::getDownloadBytesSaved).max().orElse(1);
        if (maxBytesSaved <= 0) maxBytesSaved = 1;

        int maxCpu = results.stream().mapToInt(ProbeResult::getCpuAvailable).max().orElse(1);
        if (maxCpu <= 0) maxCpu = 1;
        
        long maxMem = results.stream().mapToLong(ProbeResult::getMemAvailable).max().orElse(1);
        if (maxMem <= 0) maxMem = 1;

        final long finalMaxBytesSaved = maxBytesSaved;
        final int finalMaxCpu = maxCpu;
        final long finalMaxMem = maxMem;

        ProbeResult bestResult;
        NodeId bestNode;
        synchronized (placementLock) {
            results.sort((a, b) -> {
                int loadA = assignmentLoad(a.getNodeId());
                int loadB = assignmentLoad(b.getNodeId());
                double scoreA = computeScore(a, config, finalMaxCpu, finalMaxMem, finalMaxBytesSaved, loadA);
                double scoreB = computeScore(b, config, finalMaxCpu, finalMaxMem, finalMaxBytesSaved, loadB);
                int cmp = Double.compare(scoreB, scoreA); // Descending
                if (cmp != 0) return cmp;
                
                // Tie-breaking
                // 1. Locality (download_bytes_saved) - highest wins
                int locCmp = Long.compare(b.getDownloadBytesSaved(), a.getDownloadBytesSaved());
                if (locCmp != 0) return locCmp;
                
                // 2. Running jobs - lowest wins
                int jobCmp = Integer.compare(a.getRunningJobs(), b.getRunningJobs());
                if (jobCmp != 0) return jobCmp;

                int loadCmp = Integer.compare(loadA, loadB);
                if (loadCmp != 0) return loadCmp;
                
                // 3. Hash (job_id ^ node_id)
                int hashA = spec.getJobId().hashCode() ^ a.getNodeId().hashCode();
                int hashB = spec.getJobId().hashCode() ^ b.getNodeId().hashCode();
                return Integer.compare(hashA, hashB);
            });

            bestResult = results.get(0);
            bestNode = NodeId.of(bestResult.getNodeId().toByteArray());
            trackAssignment(spec.getJobId(), bestResult.getNodeId());
        }

        if (System.getProperty("aegis.test.kill_after_probe") != null) {
            log.info("TEST HOOK: Killing leader after probe! Reservation leaked on {}", bestNode.shortId());
            System.exit(1);
        }

        JobRecord record = JobRecord.newBuilder()
                .setSpec(spec)
                .setExecutionId(executionId)
                .setState(JobState.QUEUED)
                .setAssignedNodeId(ByteString.copyFrom(bestNode.toBytes()))
                .setCheckpointFileId(checkpointFileId != null ? checkpointFileId : "")
                .build();

        totalDownloadBytesSaved.addAndGet(bestResult.getDownloadBytesSaved());
        if (bestResult.getDownloadBytesSaved() > 0) {
            localityWins.incrementAndGet();
        }

        try {
            consensus.propose(StateCommand.newBuilder()
                    .setType(CommandType.ASSIGN_JOB)
                    .setPayload(record.toByteString())
                    .build()).get(ASSIGN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            releaseTrackedAssignment(spec.getJobId(), bestResult.getNodeId());
            throw e;
        }
                
        double bestScore = computeScore(bestResult, config, finalMaxCpu, finalMaxMem, finalMaxBytesSaved,
                assignmentLoad(bestResult.getNodeId()));
        log.info("Scheduled job {} on {} (score {:.2f}, locality {} bytes)", spec.getJobId(), bestNode.shortId(), bestScore, bestResult.getDownloadBytesSaved());

        // Release soft reservations on losing nodes immediately (don't wait for TTL)
        for (ProbeResult loser : results) {
            if (!loser.getNodeId().equals(bestResult.getNodeId())) {
                NodeId loserId = NodeId.of(loser.getNodeId().toByteArray());
                if (loserId.equals(self)) {
                    allocator.releaseAllocation(spec.getJobId());
                }
                // Remote nodes' reservations expire via TTL — we can't directly reach their allocator
            }
        }

        return bestNode;
    }

    private double computeScore(ProbeResult r, SchedulerConfig config, int maxCpu, long maxMem, long maxBytes, int assignedLoad) {
        double cpuRatio = (double) r.getCpuAvailable() / maxCpu;
        double memRatio = (double) r.getMemAvailable() / maxMem;
        double localityRatio = (double) r.getDownloadBytesSaved() / maxBytes;
        double loadScore = 1.0 / (1.0 + Math.max(0, r.getRunningJobs() + assignedLoad));
        
        return config.cpuWeight() * cpuRatio +
               config.memWeight() * memRatio +
               config.loadWeight() * loadScore +
               config.localityWeight() * localityRatio;
    }

    private ProbeResult probe(NodeId candidate, JobSpec spec, String checkpointFileId, long epoch) {
        if (candidate.equals(self)) {
            boolean accept = acceptProbe.getAsBoolean() && allocator.tryReserve(spec.getJobId(), epoch, spec.getResources()) != null;
            long downloadBytesSaved = localityProvider.getDownloadBytesSaved(
                spec.getArtifactsList().stream().map(com.aegisos.proto.ArtifactReference::getSha256).toList(), checkpointFileId);
            return ProbeResult.newBuilder()
                    .setNodeId(ByteString.copyFrom(self.toBytes()))
                    .setAccepted(accept)
                    .setCpuAvailable(allocator.getAvailableCpu())
                    .setMemAvailable(allocator.getAvailableMem())
                    .setRunningJobs(localityProvider.getRunningJobs())
                    .setDownloadBytesSaved(downloadBytesSaved)
                    .build();
        }
        try {
            ProbeRequest req = ProbeRequest.newBuilder()
                    .setJobId(spec.getJobId())
                    .setResources(spec.getResources())
                    .addAllArtifactSha256S(spec.getArtifactsList().stream().map(com.aegisos.proto.ArtifactReference::getSha256).toList())
                    .setCheckpointFileId(checkpointFileId == null ? "" : checkpointFileId)
                    .build();
            AegisMessage reply = network.request(candidate, MessageType.PROBE,
                    req.toByteArray(), PROBE_TIMEOUT_MS).get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return ProbeResult.parseFrom(reply.payload());
        } catch (Exception e) {
            return null;
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
            long downloadBytesSaved = localityProvider.getDownloadBytesSaved(
                req.getArtifactSha256SList(), req.getCheckpointFileId());
            
            ProbeResult result = ProbeResult.newBuilder()
                    .setNodeId(ByteString.copyFrom(self.toBytes()))
                    .setAccepted(accept)
                    .setCpuAvailable(allocator.getAvailableCpu())
                    .setMemAvailable(allocator.getAvailableMem())
                    .setRunningJobs(localityProvider.getRunningJobs())
                    .setDownloadBytesSaved(downloadBytesSaved)
                    .build();
            return new AegisMessage(null, msg.sender(), MessageType.PROBE_RESULT, result.toByteArray());
        } catch (Exception e) {
            log.error("Failed to parse probe", e);
            return null;
        }
    }

    private int assignmentLoad(ByteString nodeId) {
        java.util.concurrent.atomic.AtomicInteger load = assignmentLoad.get(nodeId);
        return load == null ? 0 : load.get();
    }

    private void trackAssignment(String jobId, ByteString nodeId) {
        ByteString previous = activeJobAssignments.put(jobId, nodeId);
        if (previous != null && !previous.equals(nodeId)) {
            decrementAssignmentLoad(previous);
        }
        if (previous == null || !previous.equals(nodeId)) {
            assignmentLoad.computeIfAbsent(nodeId, ignored -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            if (metricsRegistry != null) {
                metricsRegistry.counter("aegis_scheduler_assignments_total").increment();
            }
        }
    }

    private void releaseTrackedAssignment(String jobId) {
        ByteString nodeId = activeJobAssignments.remove(jobId);
        if (nodeId != null) {
            decrementAssignmentLoad(nodeId);
        }
    }

    private void releaseTrackedAssignment(String jobId, ByteString expectedNodeId) {
        if (activeJobAssignments.remove(jobId, expectedNodeId)) {
            decrementAssignmentLoad(expectedNodeId);
        }
    }

    private void decrementAssignmentLoad(ByteString nodeId) {
        assignmentLoad.computeIfPresent(nodeId, (ignored, load) -> load.decrementAndGet() <= 0 ? null : load);
    }

    public void stop() {
        acceptProbe = () -> false;
    }

    @Override
    public void close() {
        stop();
        probeExecutor.shutdownNow();
        try {
            probeExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

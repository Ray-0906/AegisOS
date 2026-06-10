package com.aegisos.node;

import com.aegisos.api.AegisOS;
import com.aegisos.api.ClusterInfo;
import com.aegisos.api.ProcessManager;
import com.aegisos.consensus.ConsensusModule;
import com.aegisos.core.identity.IdentityService;
import com.aegisos.core.identity.KeyStore;
import com.aegisos.core.message.MessageType;
import com.aegisos.discovery.DiscoveryService;
import com.aegisos.fs.AegisFS;
import com.aegisos.fs.audit.StorageAuditScheduler;
import com.aegisos.fs.audit.RepairProposer;
import com.aegisos.network.NetworkLayer;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.JobState;
import com.aegisos.proto.JobUpdate;
import com.aegisos.proto.StateCommand;
import com.aegisos.runtime.ArtifactCache;
import com.aegisos.runtime.ArtifactClassLoader;
import com.aegisos.runtime.ArtifactRegistry;
import com.aegisos.runtime.JobSupervisor;
import com.aegisos.runtime.ProcessRuntimeAgent;
import com.aegisos.scheduler.NodeResourcesView;
import com.aegisos.scheduler.ResourceAllocator;
import com.aegisos.scheduler.ResourceReporter;
import com.aegisos.scheduler.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * A running AegisOS node: a single JVM process containing every layer.
 * Layers are started in dependency order (design section 4, "Node Startup Sequence").
 *
 * <p>This class grows phase by phase; each layer is wired in as it is implemented.
 */
public final class AegisNode implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AegisNode.class);

    private final NodeConfig config;
    private final IdentityService identity;
    private final NetworkLayer network;
    private DiscoveryService discovery;
    private ConsensusModule consensus;
    private AegisFS fileSystem;
    private ArtifactRegistry artifactRegistry;
    private ArtifactCache artifactCache;
    private ArtifactClassLoader artifactClassLoader;
    private StorageAuditScheduler auditScheduler;
    private ResourceReporter resourceReporter;
    private Scheduler scheduler;
    private ResourceAllocator resourceAllocator;
    private ProcessRuntimeAgent runtimeAgent;
    private JobSupervisor jobSupervisor;
    private ProcessManager processManager;
    private AegisOS aegisOS;

    private volatile boolean started;
    private MetricsServer metricsServer;

    public AegisNode(NodeConfig config) {
        this.config = config;
        KeyStore keyStore = new KeyStore(config.homeDir());
        this.identity = IdentityService.bootstrap(keyStore);
        this.network = new NetworkLayer(identity, config.port(), config.advertiseHost());
    }

    public synchronized void start() throws IOException {
        if (started) {
            return;
        }
        log.info("Starting node {}", identity.nodeId());
        network.start();

        String selfAddress = config.advertiseHost() + ":" + network.boundPort();
        discovery = new DiscoveryService(network, identity, selfAddress, config.role());
        discovery.start(config.seeds());

        java.util.function.Supplier<java.util.List<com.aegisos.core.identity.NodeId>> allPeers = () ->
                discovery.membership().allPeers().stream()
                        .map(peer -> com.aegisos.core.identity.NodeId.of(peer.getNodeId().toByteArray()))
                        .filter(peerId -> !peerId.equals(identity.nodeId()))
                        .toList();

        java.util.function.Supplier<java.util.List<com.aegisos.core.identity.NodeId>> votingPeers = () -> {
            if (consensus == null || consensus.clusterConfiguration() == null) {
                return java.util.List.of();
            }
            return consensus.clusterConfiguration().voters().stream()
                    .filter(peerId -> !peerId.equals(identity.nodeId()))
                    .toList();
        };

        java.util.function.BooleanSupplier isVotingMember = () -> {
            if (consensus == null || consensus.clusterConfiguration() == null) {
                return false;
            }
            return consensus.clusterConfiguration().isVoter(identity.nodeId());
        };

        consensus = new ConsensusModule(network, identity.nodeId(), config.raftDir(),
                votingPeers, allPeers, isVotingMember, config.bootstrap(),
                config.membershipLagThreshold(),
                nodeId -> discovery.membership().statusOf(nodeId));

        // 1. Construct all subsystems and wire dependencies
        artifactRegistry = new ArtifactRegistry();
        
        fileSystem = new AegisFS(network, consensus, discovery, identity.nodeId(),
                config.clusterKey(), config.replicationFactor(), config.chunkDir());
                
        artifactCache = new ArtifactCache(config.artifactCacheDir(), fileSystem, config.artifactCacheSizeMb() * 1024L * 1024L);
        artifactClassLoader = new ArtifactClassLoader(artifactCache);



        auditScheduler = new StorageAuditScheduler(fileSystem, discovery, network, identity.nodeId(), consensus::isLeader, config.auditIntervalSeconds());

        if (config.repairEnabled()) {
            RepairProposer proposer = new RepairProposer(
                auditScheduler,
                consensus,
                fileSystem,
                discovery,
                network,
                identity.nodeId(),
                fileSystem.repairTaskStore(),
                config.repairRecommendationMaxAgeSeconds() * 1000L,
                config.repairTaskTimeoutSeconds() * 1000L
            );
            auditScheduler.setRepairProposer(proposer);
        }

        NodeResourcesView resourcesView = new NodeResourcesView();
        runtimeAgent = new ProcessRuntimeAgent(consensus, network, identity.nodeId(), fileSystem,
                artifactRegistry, artifactClassLoader, config.workspaceDir(), config.workspaceCleanupDelaySeconds());

        long maxMem = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        int cores = Runtime.getRuntime().availableProcessors();
        resourceAllocator = new ResourceAllocator(cores, maxMem);

        scheduler = new Scheduler(network, discovery, consensus, resourcesView, resourceAllocator, identity.nodeId());
        scheduler.setAcceptProbe(runtimeAgent::canAccept);

        resourceReporter = new ResourceReporter(network, discovery, identity.nodeId(),
                config.chunkDir(), resourcesView, runtimeAgent::runningJobs,
                ResourceReporter.DEFAULT_INTERVAL_MS);

        // Note: CheckpointManager removed in Sprint 8 in favor of manual ctx.checkpoint()
        // Note: CheckpointManager removed in Sprint 8 in favor of manual ctx.checkpoint()

        if (config.jobSupervisorEnabled()) {
            jobSupervisor = new JobSupervisor(discovery, consensus, scheduler,
                    network, identity.nodeId(), runtimeAgent);
        }

        processManager = new ProcessManager(network, scheduler, runtimeAgent, identity.nodeId(), fileSystem);
        aegisOS = new AegisOS(fileSystem, processManager, new ClusterInfo(discovery));

        // 2. Configure Snapshots
        consensus.stateMachine().registerSnapshotParticipant(consensus.clusterConfiguration());
        consensus.stateMachine().registerSnapshotParticipant(artifactRegistry);
        consensus.stateMachine().registerSnapshotParticipant(runtimeAgent.registry());
        consensus.stateMachine().registerSnapshotParticipant(fileSystem.fileIndex());
        consensus.stateMachine().registerSnapshotParticipant(fileSystem.repairTaskStore());

        consensus.raftNode().configureSnapshots(
                config.raftDir().resolve("snapshots"),
                config.snapshotEntryThreshold(),
                config.snapshotSizeThresholdBytes(),
                consensus.stateMachine()::takeSnapshot,
                consensus.stateMachine()::loadSnapshot
        );

        // 3. Register all state machine appliers BEFORE log replay or Raft start
        artifactRegistry.registerWith(consensus.stateMachine());
        fileSystem.registerAppliers(); // We will add this to AegisFS
        runtimeAgent.registerAppliers(); // We will add this to ProcessRuntimeAgent
        scheduler.registerAppliers();

        // 4. Eagerly replay persisted Raft log through all registered state-machine appliers
        // (FileIndex, JobRegistry, ArtifactRegistry) before the node starts accepting work.
        consensus.replayFromLog();

        // 4b. Rehydrate ResourceAllocator from the JobRegistry's active jobs
        resourceAllocator.clear();
        for (com.aegisos.proto.JobRecord job : runtimeAgent.registry().activeJobs()) {
            resourceAllocator.commitHardAllocation(job.getSpec().getJobId(), job.getSpec().getResources());
        }

        // 5. Start all background subsystems now that in-memory state is fully populated
        fileSystem.start();
        auditScheduler.start();
        runtimeAgent.start();
        network.registerHandler(MessageType.RUN_JOB, runtimeAgent::onRunJob);
        
        QueryHandler queryHandler = new QueryHandler(discovery, fileSystem);
        network.registerHandler(MessageType.CLIENT_QUERY, queryHandler::handle);
        scheduler.start();
        resourceReporter.start();
        if (jobSupervisor != null) {
            jobSupervisor.start();
        }
        consensus.start(); // Start Raft last to avoid heartbeat races triggering applyCommitted early

        if (config.apiPort() >= 0) {
            metricsServer = new MetricsServer(this, config.apiPort());
            metricsServer.start();
        }

        started = true;
        log.info("Node {} READY (port {})", identity.nodeId().shortId(), network.boundPort());
    }

    public IdentityService identity() {
        return identity;
    }

    public NetworkLayer network() {
        return network;
    }

    public MetricsServer metrics() {
        return metricsServer;
    }

    public DiscoveryService discovery() {
        return discovery;
    }

    public ConsensusModule consensus() {
        return consensus;
    }

    public AegisFS fileSystem() {
        return fileSystem;
    }

    public ArtifactRegistry artifactRegistry() {
        return artifactRegistry;
    }

    public ArtifactCache artifactCache() {
        return artifactCache;
    }

    public JobSupervisor jobSupervisor() {
        return jobSupervisor;
    }

    public ArtifactClassLoader artifactClassLoader() {
        return artifactClassLoader;
    }

    public AegisOS api() {
        return aegisOS;
    }

    public ProcessManager processManager() {
        return processManager;
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    public StorageAuditScheduler auditScheduler() {
        return auditScheduler;
    }

    public ResourceAllocator resourceAllocator() {
        return resourceAllocator;
    }

    public ProcessRuntimeAgent runtimeAgent() {
        return runtimeAgent;
    }

    private void recordCheckpoint(String jobId, long executionId, String checkpointPath) {
        JobUpdate update = JobUpdate.newBuilder()
                .setJobId(jobId)
                .setExecutionId(executionId)
                .setState(JobState.RUNNING)
                .setCheckpointFileId(checkpointPath)
                .build();
        consensus.propose(StateCommand.newBuilder()
                .setType(CommandType.UPDATE_JOB)
                .setPayload(update.toByteString())
                .build());
    }

    public NodeConfig config() {
        return config;
    }

    public boolean isStarted() {
        return started;
    }

    @Override
    public synchronized void close() {
        if (!started) {
            return;
        }
        log.info("Shutting down node {}", identity.nodeId().shortId());
        // #region agent log
        com.aegisos.core.util.DebugLogger.log("AegisNode.java:306", "AegisNode.close() start",
            java.util.Map.of("nodeId", identity.nodeId().shortId(), "started", started), "C", "pre-fix");
        // #endregion
        if (metricsServer != null) {
            // #region agent log
            com.aegisos.core.util.DebugLogger.log("AegisNode.java:312", "Closing metricsServer", java.util.Map.of(), "B", "pre-fix");
            // #endregion
            metricsServer.close();
        }
        if (jobSupervisor != null) {
            // #region agent log
            com.aegisos.core.util.DebugLogger.log("AegisNode.java:315", "Closing jobSupervisor", java.util.Map.of(), "C", "pre-fix");
            // #endregion
            jobSupervisor.close();
        }

        if (runtimeAgent != null) {
            // #region agent log
            com.aegisos.core.util.DebugLogger.log("AegisNode.java:321", "Closing runtimeAgent", java.util.Map.of(), "A", "pre-fix");
            // #endregion
            runtimeAgent.close();
        }
        if (resourceReporter != null) {
            // #region agent log
            com.aegisos.core.util.DebugLogger.log("AegisNode.java:324", "Closing resourceReporter", java.util.Map.of(), "C", "pre-fix");
            // #endregion
            resourceReporter.close();
        }
        if (resourceAllocator != null) {
            // #region agent log
            com.aegisos.core.util.DebugLogger.log("AegisNode.java:327", "Closing resourceAllocator", java.util.Map.of(), "C", "pre-fix");
            // #endregion
            resourceAllocator.close();
        }
        if (auditScheduler != null) {
            // #region agent log
            com.aegisos.core.util.DebugLogger.log("AegisNode.java:330", "Closing auditScheduler", java.util.Map.of(), "C", "pre-fix");
            // #endregion
            auditScheduler.close();
        }

        if (fileSystem != null) {
            try {
                // #region agent log
                com.aegisos.core.util.DebugLogger.log("AegisNode.java:335", "Closing fileSystem", java.util.Map.of(), "C", "pre-fix");
                // #endregion
                fileSystem.close();
            } catch (Exception e) {
                log.warn("Error closing fileSystem: {}", e.toString());
            }
        }
        if (consensus != null) {
            // #region agent log
            com.aegisos.core.util.DebugLogger.log("AegisNode.java:341", "Closing consensus", java.util.Map.of(), "C", "pre-fix");
            // #endregion
            consensus.close();
        }
        if (discovery != null) {
            // #region agent log
            com.aegisos.core.util.DebugLogger.log("AegisNode.java:344", "Closing discovery", java.util.Map.of(), "C", "pre-fix");
            // #endregion
            discovery.close();
        }
        // #region agent log
        com.aegisos.core.util.DebugLogger.log("AegisNode.java:346", "Closing network", java.util.Map.of(), "D", "pre-fix");
        // #endregion
        network.close();
        // #region agent log
        com.aegisos.core.util.DebugLogger.log("AegisNode.java:348", "AegisNode.close() end", java.util.Map.of("nodeId", identity.nodeId().shortId()), "C", "pre-fix");
        // #endregion
        started = false;
    }
}

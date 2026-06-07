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
import com.aegisos.fs.SelfHealingReaper;
import com.aegisos.network.NetworkLayer;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.JobState;
import com.aegisos.proto.JobUpdate;
import com.aegisos.proto.StateCommand;
import com.aegisos.runtime.ArtifactCache;
import com.aegisos.runtime.ArtifactClassLoader;
import com.aegisos.runtime.ArtifactRegistry;
import com.aegisos.runtime.CheckpointManager;
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
    private SelfHealingReaper reaper;
    private ResourceReporter resourceReporter;
    private Scheduler scheduler;
    private ResourceAllocator resourceAllocator;
    private ProcessRuntimeAgent runtimeAgent;
    private CheckpointManager checkpointManager;
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
            java.util.List<com.aegisos.core.identity.NodeId> voters = discovery.membership().allPeers().stream()
                    .filter(peer -> peer.getRole() == com.aegisos.proto.NodeRole.CLUSTER_MEMBER)
                    // Exclude DEAD peers: they cannot vote and should not inflate quorum size.
                    // SUSPECT peers are still potentially alive (just slow) so they remain in the set.
                    .filter(peer -> peer.getStatus() != com.aegisos.proto.PeerStatus.DEAD)
                    .map(peer -> com.aegisos.core.identity.NodeId.of(peer.getNodeId().toByteArray()))
                    .filter(peerId -> !peerId.equals(identity.nodeId()))
                    .toList();
            java.util.List<com.aegisos.core.identity.NodeId> all = allPeers.get();
            if (voters.size() != all.size()) {
                log.info("Quorum calculation ignores non-voting/dead peers. Voting members: {}, All peers: {}",
                        voters.size() + 1, all.size() + 1);
            }
            return voters;
        };

        boolean isVotingMember = config.role() == com.aegisos.proto.NodeRole.CLUSTER_MEMBER;
        consensus = new ConsensusModule(network, identity.nodeId(), config.raftDir(),
                votingPeers, allPeers, isVotingMember);

        // 1. Construct all subsystems and wire dependencies
        artifactRegistry = new ArtifactRegistry();
        
        fileSystem = new AegisFS(network, consensus, discovery, identity.nodeId(),
                config.clusterKey(), config.replicationFactor(), config.chunkDir());
                
        artifactCache = new ArtifactCache(config.artifactCacheDir(), fileSystem);
        artifactClassLoader = new ArtifactClassLoader(artifactCache);

        reaper = new SelfHealingReaper(fileSystem, consensus, discovery, identity.nodeId(),
                config.replicationFactor(), config.reaperIntervalMs());

        NodeResourcesView resourcesView = new NodeResourcesView();
        runtimeAgent = new ProcessRuntimeAgent(consensus, network, identity.nodeId(), fileSystem,
                artifactRegistry, artifactClassLoader);

        long maxMem = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        int cores = Runtime.getRuntime().availableProcessors();
        resourceAllocator = new ResourceAllocator(cores, maxMem);

        scheduler = new Scheduler(network, discovery, consensus, resourcesView, resourceAllocator, identity.nodeId());
        scheduler.setAcceptProbe(runtimeAgent::canAccept);

        resourceReporter = new ResourceReporter(network, discovery, identity.nodeId(),
                config.chunkDir(), resourcesView, runtimeAgent::runningJobs,
                ResourceReporter.DEFAULT_INTERVAL_MS);

        checkpointManager = new CheckpointManager(identity.nodeId(), fileSystem, this::recordCheckpoint, config.checkpointIntervalMs());
        runtimeAgent.setCheckpointManager(checkpointManager);

        jobSupervisor = new JobSupervisor(discovery, consensus, scheduler,
                network, identity.nodeId(), runtimeAgent);

        processManager = new ProcessManager(network, scheduler, runtimeAgent, identity.nodeId());
        aegisOS = new AegisOS(fileSystem, processManager, new ClusterInfo(discovery));

        // 2. Register all state machine appliers BEFORE log replay or Raft start
        artifactRegistry.registerWith(consensus.stateMachine());
        fileSystem.registerAppliers(); // We will add this to AegisFS
        runtimeAgent.registerAppliers(); // We will add this to ProcessRuntimeAgent
        scheduler.registerAppliers();

        // 3. Eagerly replay persisted Raft log through all registered state-machine appliers
        // (FileIndex, JobRegistry, ArtifactRegistry) before the node starts accepting work.
        consensus.replayFromLog();

        // 4. Start all background subsystems now that in-memory state is fully populated
        fileSystem.start();
        reaper.start();
        runtimeAgent.start();
        network.registerHandler(MessageType.RUN_JOB, runtimeAgent::onRunJob);
        
        QueryHandler queryHandler = new QueryHandler(discovery, fileSystem);
        network.registerHandler(MessageType.CLIENT_QUERY, queryHandler::handle);
        scheduler.start();
        resourceReporter.start();
        jobSupervisor.start();
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
        if (metricsServer != null) {
            metricsServer.close();
        }
        if (jobSupervisor != null) {
            jobSupervisor.close();
        }
        if (checkpointManager != null) {
            checkpointManager.stop();
        }
        if (runtimeAgent != null) {
            runtimeAgent.close();
        }
        if (resourceReporter != null) {
            resourceReporter.close();
        }
        if (resourceAllocator != null) {
            resourceAllocator.close();
        }
        if (reaper != null) {
            reaper.close();
        }
        if (fileSystem != null) {
            try {
                fileSystem.close();
            } catch (Exception e) {
                log.warn("Error closing fileSystem: {}", e.toString());
            }
        }
        if (consensus != null) {
            consensus.close();
        }
        if (discovery != null) {
            discovery.close();
        }
        network.close();
        
        started = false;
    }
}

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
import com.aegisos.runtime.CheckpointManager;
import com.aegisos.runtime.MigrationCoordinator;
import com.aegisos.runtime.ProcessRuntimeAgent;
import com.aegisos.scheduler.NodeResourcesView;
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
    private SelfHealingReaper reaper;
    private ResourceReporter resourceReporter;
    private Scheduler scheduler;
    private ProcessRuntimeAgent runtimeAgent;
    private CheckpointManager checkpointManager;
    private MigrationCoordinator migrationCoordinator;
    private ProcessManager processManager;
    private AegisOS aegisOS;

    private volatile boolean started;

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
                    .map(peer -> com.aegisos.core.identity.NodeId.of(peer.getNodeId().toByteArray()))
                    .filter(peerId -> !peerId.equals(identity.nodeId()))
                    .toList();
            java.util.List<com.aegisos.core.identity.NodeId> all = allPeers.get();
            if (voters.size() != all.size()) {
                log.info("Quorum calculation ignores non-voting peers. Voting members: {}, All peers: {}",
                        voters.size() + 1, all.size() + 1);
            }
            return voters;
        };

        boolean isVotingMember = config.role() == com.aegisos.proto.NodeRole.CLUSTER_MEMBER;
        consensus = new ConsensusModule(network, identity.nodeId(), config.raftDir(),
                votingPeers, allPeers, isVotingMember);
        consensus.start();

        fileSystem = new AegisFS(network, consensus, discovery, identity.nodeId(),
                config.clusterKey(), config.replicationFactor(), config.chunkDir());
        fileSystem.start();

        reaper = new SelfHealingReaper(fileSystem, consensus, discovery, identity.nodeId(),
                config.replicationFactor(), config.reaperIntervalMs());
        reaper.start();

        NodeResourcesView resourcesView = new NodeResourcesView();
        runtimeAgent = new ProcessRuntimeAgent(consensus, identity.nodeId(), fileSystem);
        runtimeAgent.start();
        network.registerHandler(MessageType.RUN_JOB, runtimeAgent::onRunJob);

        scheduler = new Scheduler(network, discovery, consensus, resourcesView, identity.nodeId());
        scheduler.setAcceptProbe(runtimeAgent::canAccept);
        scheduler.start();

        resourceReporter = new ResourceReporter(network, discovery, identity.nodeId(),
                config.chunkDir(), resourcesView, runtimeAgent::runningJobs,
                ResourceReporter.DEFAULT_INTERVAL_MS);
        resourceReporter.start();

        checkpointManager = new CheckpointManager(fileSystem, identity.nodeId(),
                config.checkpointIntervalMs(), this::recordCheckpoint);
        runtimeAgent.setCheckpointManager(checkpointManager);

        migrationCoordinator = new MigrationCoordinator(discovery, consensus, scheduler,
                network, identity.nodeId(), runtimeAgent);
        migrationCoordinator.start();

        processManager = new ProcessManager(network, scheduler, runtimeAgent, identity.nodeId());
        aegisOS = new AegisOS(fileSystem, processManager, new ClusterInfo(discovery));

        started = true;
        log.info("Node {} READY (port {})", identity.nodeId().shortId(), network.boundPort());
    }

    public IdentityService identity() {
        return identity;
    }

    public NetworkLayer network() {
        return network;
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

    public AegisOS api() {
        return aegisOS;
    }

    public ProcessManager processManager() {
        return processManager;
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    public ProcessRuntimeAgent runtimeAgent() {
        return runtimeAgent;
    }

    private void recordCheckpoint(String jobId, String checkpointPath) {
        JobUpdate update = JobUpdate.newBuilder()
                .setJobId(jobId)
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
        if (migrationCoordinator != null) {
            migrationCoordinator.close();
        }
        if (checkpointManager != null) {
            checkpointManager.close();
        }
        if (resourceReporter != null) {
            resourceReporter.close();
        }
        if (reaper != null) {
            reaper.close();
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

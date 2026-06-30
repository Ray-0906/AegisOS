package com.aegisos.runtime.core;

import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.api.runtime.ProcessTable;
import com.aegisos.consensus.ConsensusModule;
import com.aegisos.core.identity.NodeId;
import com.aegisos.discovery.DiscoveryService;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.PeerStatus;
import com.aegisos.proto.ProcessRecordProto;
import com.aegisos.proto.StateCommand;
import com.aegisos.runtime.util.ProcessMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TopologyReconciler implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(TopologyReconciler.class);

    private final ProcessTable processTable;
    private final ConsensusModule consensus;
    private final DiscoveryService discovery;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "topology-reconciler");
        t.setDaemon(true);
        return t;
    });

    public TopologyReconciler(ProcessTable processTable, ConsensusModule consensus, DiscoveryService discovery) {
        this.processTable = processTable;
        this.consensus = consensus;
        this.discovery = discovery;
    }

    public void start() {
        // Poll every 5 seconds as approved by the Chief Architect
        executor.scheduleWithFixedDelay(this, 5, 5, TimeUnit.SECONDS);
        log.info("TopologyReconciler daemon started.");
    }

    public void stop() {
        executor.shutdownNow();
    }

    @Override
    public void run() {
        try {
            if (!consensus.isLeader()) {
                return;
            }

            for (ProcessRecord record : processTable.list()) {
                if (record.state() == ProcessState.PLACED || record.state() == ProcessState.RUNNING) {
                    if (record.ownerNodeId() == null || record.ownerNodeId().isEmpty()) {
                        continue;
                    }

                    NodeId owner;
                    try {
                        owner = NodeId.fromHex(record.ownerNodeId());
                    } catch (IllegalArgumentException e) {
                        log.error("Invalid owner NodeId {} for process {}", record.ownerNodeId(), record.processId());
                        continue;
                    }

                    PeerStatus status = discovery.membership().statusOf(owner);

                    if (status == PeerStatus.DEAD || status == PeerStatus.PEER_UNKNOWN) {
                        log.warn("Process {} is a GHOST on dead node {}. Emitting FAILED.", record.processId(), owner.shortId());

                        ProcessRecord failedRecord = new ProcessRecord(
                                record.processId(),
                                record.artifactId(),
                                record.ownerNodeId(),
                                record.submitterNodeId(),
                                record.executionId(),
                                ProcessState.FAILED,
                                record.resources(),
                                record.submitTimestamp(),
                                System.currentTimeMillis(),
                                record.executionCommand(),
                                record.pipeToProcessId(),
                                record.resourceConstraints(),
                                record.placementConstraints(),
                                record.serviceName(),
                                record.pipeToService(),
                                record.traceId()
                        );

                        ProcessRecordProto proto = ProcessMapper.toProto(failedRecord);
                        StateCommand cmd = StateCommand.newBuilder()
                                .setType(CommandType.UPDATE_PROCESS_STATE)
                                .setPayload(proto.toByteString())
                                .build();

                        consensus.propose(cmd).exceptionally(ex -> {
                            log.error("Failed to propose FAILED state for ghost process {}", record.processId(), ex);
                            return null;
                        });
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error in TopologyReconciler loop", e);
        }
    }
}

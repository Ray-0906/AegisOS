package com.aegisos.runtime.core;

import com.aegisos.api.NodeInfo;
import com.aegisos.api.runtime.PlacementDecision;
import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessScheduler;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.api.runtime.ProcessStateListener;
import com.aegisos.api.runtime.ProcessTable;
import com.aegisos.consensus.ConsensusModule;
import com.aegisos.core.identity.IdentityService;
import com.aegisos.discovery.gossip.MembershipList;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.PeerEntry;
import com.aegisos.proto.PeerStatus;
import com.aegisos.proto.ProcessRecordProto;
import com.aegisos.proto.StateCommand;
import com.aegisos.runtime.util.ProcessMapper;
import com.aegisos.scheduler.ConstraintScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SimpleProcessScheduler implements ProcessScheduler, ProcessStateListener {
    private static final Logger log = LoggerFactory.getLogger(SimpleProcessScheduler.class);
    
    private final ConsensusModule consensus;
    private final IdentityService identityService;
    private final MembershipList membershipList;
    private final ProcessTable processTable;
    private final ConstraintScheduler constraintScheduler;

    public SimpleProcessScheduler(ConsensusModule consensus, IdentityService identityService, MembershipList membershipList, ProcessTable processTable) {
        this.consensus = consensus;
        this.identityService = identityService;
        this.membershipList = membershipList;
        this.processTable = processTable;
        this.constraintScheduler = new ConstraintScheduler();
    }

    @Override
    public PlacementDecision evaluate(ProcessRecord process) {
        return null;
    }

    @Override
    public void onProcessStateChanged(ProcessRecord record) {
        if (record.state() == ProcessState.SUBMITTED) {
            log.info("[Scheduler Daemon] Reacting to SUBMITTED: {}", record.processId());
            
            List<NodeInfo> activeNodes = new ArrayList<>();
            for (PeerEntry peer : membershipList.allPeers()) {
                if (peer.getStatus() == PeerStatus.ALIVE && peer.hasResources()) {
                    long freeMem = peer.getResources().getMemoryTotalMb() - peer.getResources().getMemoryUsedMb();
                    int cores = peer.getResources().getCpuCores();
                    activeNodes.add(new NodeInfo(
                        com.aegisos.core.util.HexUtil.encode(peer.getNodeId().toByteArray()),
                        peer.getAddress(),
                        peer.getStatus().name(),
                        new com.aegisos.core.telemetry.TelemetrySnapshot(cores, freeMem, false)
                    ));
                }
            }

            ProcessRecordProto processReq = ProcessMapper.toProto(record);
            Optional<String> optimalNode = constraintScheduler.findOptimalNode(processReq, activeNodes, processTable);

            ProcessRecord nextRecord;
            if (optimalNode.isEmpty()) {
                log.warn("SCHEDULING_FAILED: Insufficient cluster resources or constraint violation for process {}", record.processId());
                nextRecord = new ProcessRecord(
                        record.processId(),
                        record.artifactId(),
                        null,
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
            } else {
                String assignedNodeId = optimalNode.get();
                nextRecord = new ProcessRecord(
                        record.processId(),
                        record.artifactId(),
                        assignedNodeId,
                        record.submitterNodeId(),
                        record.executionId(),
                        ProcessState.PLACED,
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
            }

            try {
                ProcessRecordProto proto = ProcessMapper.toProto(nextRecord);
                StateCommand cmd = StateCommand.newBuilder()
                        .setType(CommandType.UPDATE_PROCESS_STATE)
                        .setPayload(proto.toByteString())
                        .build();

                consensus.propose(cmd).exceptionally(ex -> {
                    log.error("Failed to propose new state for process {}", record.processId(), ex);
                    return null;
                });
            } catch (Exception e) {
                log.error("Failed to serialize new state for process {}", record.processId(), e);
            }
        }
    }
}

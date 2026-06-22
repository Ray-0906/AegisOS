package com.aegisos.runtime.core;

import com.aegisos.api.runtime.PlacementDecision;
import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessScheduler;
import com.aegisos.api.runtime.ProcessState;
import com.aegisos.api.runtime.ProcessStateListener;
import com.aegisos.consensus.ConsensusModule;
import com.aegisos.core.identity.IdentityService;
import com.aegisos.core.identity.NodeId;
import com.aegisos.discovery.gossip.MembershipList;
import com.aegisos.proto.CommandType;
import com.aegisos.proto.ProcessRecordProto;
import com.aegisos.proto.StateCommand;
import com.aegisos.runtime.util.ProcessMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class SimpleProcessScheduler implements ProcessScheduler, ProcessStateListener {
    private static final Logger log = LoggerFactory.getLogger(SimpleProcessScheduler.class);
    
    private final ConsensusModule consensus;
    private final IdentityService identityService;
    private final MembershipList membershipList;

    public SimpleProcessScheduler(ConsensusModule consensus, IdentityService identityService, MembershipList membershipList) {
        this.consensus = consensus;
        this.identityService = identityService;
        this.membershipList = membershipList;
    }

    @Override
    public PlacementDecision evaluate(ProcessRecord process) {
        List<NodeId> candidates = new ArrayList<>();
        candidates.add(identityService.nodeId());
        candidates.addAll(membershipList.alivePeerIds());

        int idx = ThreadLocalRandom.current().nextInt(candidates.size());
        NodeId selected = candidates.get(idx);
        return new PlacementDecision(selected.toHex());
    }

    @Override
    public void onProcessStateChanged(ProcessRecord record) {
        if (record.state() == ProcessState.SUBMITTED) {
            log.info("[Scheduler Daemon] Reacting to SUBMITTED: {}", record.processId());
            PlacementDecision decision = evaluate(record);
            
            ProcessRecord placedRecord = new ProcessRecord(
                    record.processId(),
                    record.artifactId(),
                    decision.assignedNodeId(),
                    record.executionId(),
                    ProcessState.PLACED,
                    record.resources(),
                    record.submitTimestamp(),
                    System.currentTimeMillis()
            );

            try {
                ProcessRecordProto proto = ProcessMapper.toProto(placedRecord);
                StateCommand cmd = StateCommand.newBuilder()
                        .setType(CommandType.UPDATE_PROCESS_STATE)
                        .setPayload(proto.toByteString())
                        .build();

                consensus.propose(cmd).exceptionally(ex -> {
                    log.error("Failed to propose PLACED state for process {}", record.processId(), ex);
                    return null;
                });
            } catch (Exception e) {
                log.error("Failed to serialize PLACED state for process {}", record.processId(), e);
            }
        }
    }
}

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
import com.aegisos.proto.PeerEntry;
import com.aegisos.proto.PeerStatus;
import com.aegisos.proto.ProcessRecordProto;
import com.aegisos.proto.StateCommand;
import com.aegisos.runtime.util.ProcessMapper;
import com.aegisos.core.util.HexUtil;
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
        return null;
    }

    @Override
    public void onProcessStateChanged(ProcessRecord record) {
        if (record.state() == ProcessState.SUBMITTED) {
            log.info("[Scheduler Daemon] Reacting to SUBMITTED: {}", record.processId());
            
            long reqMem = record.resources().memoryMb();
            int reqCpu = record.resources().cpuCores();
            
            List<PeerEntry> candidates = new ArrayList<>();
            for (PeerEntry peer : membershipList.allPeers()) {
                if (peer.getStatus() == PeerStatus.ALIVE && peer.hasResources()) {
                    long freeMem = peer.getResources().getMemoryTotalMb() - peer.getResources().getMemoryUsedMb();
                    int cores = peer.getResources().getCpuCores();
                    if (freeMem >= reqMem && cores >= reqCpu) {
                        candidates.add(peer);
                    }
                }
            }

            ProcessRecord nextRecord;
            if (candidates.isEmpty()) {
                log.warn("Scheduling failed: Insufficient cluster resources for process {}", record.processId());
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
                        record.pipeToProcessId()
                );
            } else {
                int idx = ThreadLocalRandom.current().nextInt(candidates.size());
                PeerEntry selected = candidates.get(idx);
                String assignedNodeId = HexUtil.encode(selected.getNodeId().toByteArray());
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
                        record.pipeToProcessId()
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

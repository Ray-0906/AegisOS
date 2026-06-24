package com.aegisos.scheduler;

import com.aegisos.api.NodeInfo;
import com.aegisos.api.runtime.ProcessTable;
import com.aegisos.proto.ProcessRecordProto;
import com.aegisos.proto.PlacementConstraintsProto;
import com.aegisos.proto.ResourceConstraintsProto;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.HexFormat;

public class ConstraintScheduler {

    public Optional<String> findOptimalNode(
            ProcessRecordProto processReq, 
            List<NodeInfo> activeNodes, 
            ProcessTable processTable) {
        
        ResourceConstraintsProto resources = processReq.hasResourceConstraints() ? processReq.getResourceConstraints() : null;
        PlacementConstraintsProto placement = processReq.hasPlacementConstraints() ? processReq.getPlacementConstraints() : null;

        return activeNodes.stream()
                .filter(node -> node.getTelemetry() != null)
                .filter(node -> {
                    if (resources != null) {
                        if (node.getTelemetry().getAvailableCpuCores() < resources.getRequiredCpuCores()) {
                            return false;
                        }
                        if (node.getTelemetry().getAvailableMemoryMb() < resources.getRequiredMemoryMb()) {
                            return false;
                        }
                        if (resources.getRequireGpu() && !node.getTelemetry().hasGpu()) {
                            return false;
                        }
                    }
                    return true;
                })
                .filter(node -> {
                    if (placement != null) {
                        String nodeIdHex = HexFormat.of().formatHex(node.getNodeId());
                        
                        if (placement.getTargetNodeId() != null && !placement.getTargetNodeId().isEmpty()) {
                            if (!nodeIdHex.equals(placement.getTargetNodeId())) {
                                return false;
                            }
                        }
                        
                        if (placement.getAntiAffinityProcessId() != null && !placement.getAntiAffinityProcessId().isEmpty()) {
                            var antiProcess = processTable.lookup(placement.getAntiAffinityProcessId());
                            if (antiProcess.isPresent() && nodeIdHex.equals(antiProcess.get().ownerNodeId())) {
                                return false; 
                            }
                        }
                    }
                    return true;
                })
                .max(Comparator.comparingLong(n -> n.getTelemetry().getAvailableMemoryMb()))
                .map(node -> HexFormat.of().formatHex(node.getNodeId()));
    }
}

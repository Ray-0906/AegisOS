package com.aegisos.api;

import com.aegisos.core.telemetry.TelemetrySnapshot;
import java.util.HexFormat;

public record NodeInfo(String nodeId, String address, String status, TelemetrySnapshot telemetry) {
    
    // For backwards compatibility with places expecting 3 args
    public NodeInfo(String nodeId, String address, String status) {
        this(nodeId, address, status, null);
    }

    // Required by ConstraintScheduler
    public byte[] getNodeId() {
        return HexFormat.of().parseHex(nodeId);
    }

    public TelemetrySnapshot getTelemetry() {
        return telemetry;
    }
}

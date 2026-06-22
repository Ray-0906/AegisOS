package com.aegisos.runtime.core;

import com.aegisos.api.runtime.PlacementDecision;
import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.ProcessScheduler;

public class SimpleProcessScheduler implements ProcessScheduler {
    @Override
    public PlacementDecision evaluate(ProcessRecord process) {
        return new PlacementDecision("local-node");
    }
}

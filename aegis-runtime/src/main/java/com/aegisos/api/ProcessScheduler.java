package com.aegisos.api.runtime;

public interface ProcessScheduler {
    PlacementDecision evaluate(ProcessRecord process);
}

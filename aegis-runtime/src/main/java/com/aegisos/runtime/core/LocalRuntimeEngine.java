package com.aegisos.runtime.core;

import com.aegisos.api.runtime.ProcessRecord;
import com.aegisos.api.runtime.RuntimeEngine;

public class LocalRuntimeEngine implements RuntimeEngine {
    @Override
    public void start(ProcessRecord process) {
    }

    @Override
    public void stop(String processId) {
    }

    @Override
    public void pause(String processId) {
    }

    @Override
    public void checkpoint(String processId) {
    }
}

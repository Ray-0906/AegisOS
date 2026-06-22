package com.aegisos.api.runtime;

public interface ProcessStateListener {
    void onProcessStateChanged(ProcessRecord record);
}

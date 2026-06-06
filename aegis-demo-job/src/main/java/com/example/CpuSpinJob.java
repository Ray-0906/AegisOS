package com.example;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
import java.io.Serializable;

public class CpuSpinJob implements AegisJob<String> {
    public CpuSpinJob() {}
    public CpuSpinJob(String[] args) {}

    @Override
    public String execute(JobContext context) throws Exception {
        System.out.println("CpuSpinJob starting infinite loop...");
        while (true) {
            // spin to max out CPU
        }
    }

    @Override
    public Serializable captureState() { return null; }
    @Override
    public void restoreState(Serializable state) {}
}

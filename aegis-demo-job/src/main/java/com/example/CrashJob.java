package com.example;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;


public class CrashJob implements AegisJob<String> {
    public CrashJob() {}
    public CrashJob(String[] args) {}

    @Override
    public String execute(JobContext context) throws Exception {
        System.out.println("CrashJob about to throw RuntimeException...");
        throw new RuntimeException("Intentional E8 Crash");
    }

    
    
}

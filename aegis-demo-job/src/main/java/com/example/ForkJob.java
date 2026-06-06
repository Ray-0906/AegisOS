package com.example;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
import java.io.Serializable;

public class ForkJob implements AegisJob<String> {
    public ForkJob() {}
    public ForkJob(String[] args) {}

    @Override
    public String execute(JobContext context) throws Exception {
        System.out.println("ForkJob spawning child process...");
        // Spawn a long-running ping so we can check if it gets killed
        new ProcessBuilder("ping", "127.0.0.1", "-n", "10000").start();
        Thread.sleep(Long.MAX_VALUE);
        return "Done";
    }

    @Override
    public Serializable captureState() { return null; }
    @Override
    public void restoreState(Serializable state) {}
}

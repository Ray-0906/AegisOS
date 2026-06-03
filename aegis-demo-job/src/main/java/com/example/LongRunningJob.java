package com.example;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;

import java.io.Serializable;

public class LongRunningJob implements AegisJob<String> {

    private int progress = 0;
    private final int totalSteps = 15;

    public LongRunningJob() {}
    public LongRunningJob(String[] args) {}

    @Override
    public String execute(JobContext context) throws Exception {
        System.out.println("LongRunningJob starting on node " + context.executingNode() + ", initial progress=" + progress);
        
        while (progress < totalSteps) {
            Thread.sleep(1000); // 1 second per step
            progress++;
            System.out.println("LongRunningJob progress " + progress + "/" + totalSteps);
        }

        return "Finished! Node=" + context.executingNode() + ", Final progress=" + progress;
    }

    @Override
    public Serializable captureState() {
        return progress;
    }

    @Override
    public void restoreState(Serializable state) {
        if (state instanceof Integer) {
            this.progress = (Integer) state;
            System.out.println("LongRunningJob restored state: progress=" + progress);
        }
    }
}

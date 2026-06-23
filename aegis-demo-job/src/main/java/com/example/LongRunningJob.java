package com.example;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;



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

    

    
}

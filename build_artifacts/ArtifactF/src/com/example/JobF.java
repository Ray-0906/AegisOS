package com.example;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
public class JobF implements AegisJob<Boolean> {
    public Boolean execute(JobContext ctx) {
        System.out.println("ArtifactF running...");
        try { Thread.sleep(10000); } catch (Exception e) {}
        System.out.println("ArtifactF completed!");
        return true;
    }
}

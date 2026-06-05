package com.example;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
public class JobG implements AegisJob<String> {
    public String execute(JobContext ctx) {
        System.out.println("ArtifactG v2 running...");
        try { Thread.sleep(8000); } catch (Exception e) {}
        System.out.println("ArtifactG v2 completing...");
        return "v2";
    }
}

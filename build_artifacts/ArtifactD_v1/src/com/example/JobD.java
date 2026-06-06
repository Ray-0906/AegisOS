package com.example;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
public class JobD implements AegisJob<String> {
    public String execute(JobContext ctx) {
        System.out.println("ArtifactD version 1");
        return "v1";
    }
}

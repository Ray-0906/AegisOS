package com.example;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
public class JobD implements AegisJob<String> {
    public String execute(JobContext ctx) {
        System.out.println("ArtifactD version 2");
        return "v2";
    }
}

package com.example;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
public class JobC implements AegisJob<Boolean> {
    public Boolean execute(JobContext ctx) {
        System.out.println("ArtifactC executed");
        return true;
    }
}

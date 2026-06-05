package com.example;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
public class JobB implements AegisJob<Boolean> {
    public Boolean execute(JobContext ctx) {
        System.out.println("ArtifactB executed");
        return true;
    }
}

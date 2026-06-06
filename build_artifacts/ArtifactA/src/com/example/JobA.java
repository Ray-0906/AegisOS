package com.example;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
public class JobA implements AegisJob<Boolean> {
    public Boolean execute(JobContext ctx) {
        System.out.println("ArtifactA executed");
        return true;
    }
}

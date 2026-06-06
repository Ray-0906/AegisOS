package com.example;
import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
public class JobE implements AegisJob<String> {
    public String execute(JobContext ctx) {
        String ver = Util.getVersion();
        System.out.println("JobE running with: " + ver);
        // Sleep to allow concurrent execution
        try { Thread.sleep(5000); } catch (Exception e) {}
        System.out.println("JobE finishing with: " + Util.getVersion());
        return Util.getVersion();
    }
}

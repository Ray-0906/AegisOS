package com.example;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;
import java.io.File;
import java.io.FileWriter;


public class DiskWriteJob implements AegisJob<String> {
    public DiskWriteJob() {}
    public DiskWriteJob(String[] args) {}

    @Override
    public String execute(JobContext context) throws Exception {
        File f = new File("job_local_test.txt");
        try (FileWriter fw = new FileWriter(f)) {
            fw.write("Isolation test " + context.jobId());
        }
        System.out.println("DiskWriteJob wrote to " + f.getAbsolutePath());
        Thread.sleep(3000); // Wait so two jobs run concurrently
        return "Done";
    }

    
    
}

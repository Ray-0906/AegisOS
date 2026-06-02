package com.aegisos.cluster.jobs;

import com.aegisos.runtime.AegisJob;
import com.aegisos.runtime.JobContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Appends its jobId to a file to verify exactly-once execution. */
public final class WriteOnceJob implements AegisJob<Boolean> {

    private final String outputFile;

    public WriteOnceJob(String outputFile) {
        this.outputFile = outputFile;
    }

    public WriteOnceJob() {
        this("job_execution_log.txt");
    }

    @Override
    public Boolean execute(JobContext ctx) {
        try {
            Path path = Path.of(outputFile);
            String line = ctx.jobId() + "\n";
            Files.writeString(path, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
            // Sleep a bit so it takes time to execute (optional, but helps make sure it doesn't just instantly finish)
            Thread.sleep(100);
            
            return true;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

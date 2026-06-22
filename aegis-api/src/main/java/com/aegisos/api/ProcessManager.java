package com.aegisos.api;

import com.aegisos.proto.JobSpec;
import com.aegisos.proto.JobState;
import com.aegisos.proto.ArtifactRecord;
import com.aegisos.runtime.AegisJob;
import java.util.List;

public interface ProcessManager {
    JobHandle submit(AegisJob<?> job, int cpuCores, long memoryMb) throws Exception;
    JobHandle submitArtifact(String artifactId, String className, List<String> args, int cpuCores, long memoryMb) throws Exception;
    JobHandle submitJob(JobSpec spec) throws Exception;
    JobState status(String jobId);
    byte[] await(JobHandle handle, long timeoutMs) throws Exception;
    <T> T awaitResult(JobHandle handle, long timeoutMs) throws Exception;
    String uploadArtifact(byte[] data) throws Exception;
    ArtifactRecord getArtifact(String sha256);
    byte[] downloadArtifact(String sha256) throws Exception;
}

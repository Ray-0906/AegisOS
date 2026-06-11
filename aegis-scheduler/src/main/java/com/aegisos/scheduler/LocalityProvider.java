package com.aegisos.scheduler;

import java.util.List;

public interface LocalityProvider {
    long getDownloadBytesSaved(List<String> artifactSha256s, String checkpointFileId);
    int getRunningJobs();
}

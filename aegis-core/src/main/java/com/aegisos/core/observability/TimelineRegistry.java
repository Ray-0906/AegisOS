package com.aegisos.core.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class TimelineRegistry {
    private final int maxRecentJobs;
    private final ConcurrentHashMap<String, JobTimeline> active = new ConcurrentHashMap<>();
    
    // Bounded LRU cache for recently completed/terminal jobs
    private final Map<String, JobTimeline> recent;

    public TimelineRegistry(int maxRecentJobs) {
        this.maxRecentJobs = maxRecentJobs;
        this.recent = new LinkedHashMap<String, JobTimeline>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, JobTimeline> eldest) {
                return size() > maxRecentJobs;
            }
        };
    }

    public void recordEvent(String jobId, JobEventType type, String nodeId, String details) {
        if (jobId == null || jobId.isEmpty()) return;

        JobTimeline timeline = active.computeIfAbsent(jobId, k -> new JobTimeline(k));
        
        // If the job is moving to a terminal state (COMPLETED, FAILED, FENCED) 
        // and we receive it here, we shouldn't necessarily move it immediately if we 
        // might receive more events. But logically, FAILED/COMPLETED/FENCED are terminal.
        // We will move it to recent on terminal events.
        
        timeline.addEvent(new JobTimelineEvent(System.currentTimeMillis(), type, nodeId, details));

        if (type == JobEventType.COMPLETED || type == JobEventType.FAILED || type == JobEventType.FENCED) {
            moveToRecent(jobId);
        }
    }

    private synchronized void moveToRecent(String jobId) {
        JobTimeline timeline = active.remove(jobId);
        if (timeline != null) {
            recent.put(jobId, timeline);
        }
    }

    public Optional<JobTimeline> getTimeline(String jobId) {
        JobTimeline timeline = active.get(jobId);
        if (timeline != null) {
            return Optional.of(timeline);
        }
        synchronized (this) {
            return Optional.ofNullable(recent.get(jobId));
        }
    }
}

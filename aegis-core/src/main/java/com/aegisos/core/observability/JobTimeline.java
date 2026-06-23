package com.aegisos.core.observability;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Collections;

public class JobTimeline {
    private final String jobId;
    private final List<JobTimelineEvent> events = new CopyOnWriteArrayList<>();

    public JobTimeline(String jobId) {
        if (jobId == null || jobId.isEmpty()) {
            throw new IllegalArgumentException("jobId cannot be null or empty");
        }
        this.jobId = jobId;
    }

    public String getJobId() {
        return jobId;
    }

    public void addEvent(JobTimelineEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event cannot be null");
        }
        this.events.add(event);
    }

    public List<JobTimelineEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }
}

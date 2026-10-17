package com.aresstack.spaceharvester.application.jobs;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Flüchtige Ablage: Aufträge gehen beim Neustart verloren. Standard, bis der Lucene-Speicher (#26) angebunden ist. */
public class InMemoryJobStore implements JobStore {

    private final Map<String, JobStatus> jobs = new ConcurrentHashMap<>();

    @Override
    public void save(JobStatus status) {
        jobs.put(status.id(), status);
    }

    @Override
    public Optional<JobStatus> find(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public List<JobStatus> all() {
        return List.copyOf(jobs.values());
    }

    @Override
    public void delete(String jobId) {
        jobs.remove(jobId);
    }
}

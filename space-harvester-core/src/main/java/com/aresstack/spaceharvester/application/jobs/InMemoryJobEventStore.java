package com.aresstack.spaceharvester.application.jobs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Flüchtige Ablage der Diagnoseereignisse; Standard ohne Lucene-Ablage. */
public class InMemoryJobEventStore implements JobEventStore {

    private final Map<String, List<JobEvent>> events = new ConcurrentHashMap<>();

    @Override
    public void append(JobEvent event) {
        List<JobEvent> list = events.computeIfAbsent(event.jobId(), id -> new ArrayList<>());
        synchronized (list) {
            if (list.size() < MAX_EVENTS_PER_JOB) {
                list.add(event);
            }
        }
    }

    @Override
    public List<JobEvent> events(String jobId) {
        List<JobEvent> list = events.get(jobId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return list.stream().sorted(Comparator.comparingLong(JobEvent::sequence)).toList();
        }
    }

    @Override
    public List<JobEvent> recent(int limit) {
        List<JobEvent> all = new ArrayList<>();
        for (List<JobEvent> list : events.values()) {
            synchronized (list) {
                all.addAll(list);
            }
        }
        return all.stream().sorted(Comparator.comparing(JobEvent::at).thenComparingLong(JobEvent::sequence)
                .reversed()).limit(Math.max(0, limit)).toList();
    }

    @Override
    public void delete(String jobId) {
        events.remove(jobId);
    }
}

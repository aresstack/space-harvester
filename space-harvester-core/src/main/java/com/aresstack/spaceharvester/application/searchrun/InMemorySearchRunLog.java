package com.aresstack.spaceharvester.application.searchrun;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Flüchtiges Protokoll, solange kein Cache-Verzeichnis eingerichtet ist; auch Test-Fake. Hält höchstens
 * {@link SearchRunLog#MAX_RUNS} Läufe, nicht älter als {@code maxAge}.
 */
public final class InMemorySearchRunLog implements SearchRunLog {

    private final Map<SearchRunId, SearchRun> runs = new LinkedHashMap<>();
    private final Duration maxAge;
    private final Clock clock;
    private final int capacity;

    public InMemorySearchRunLog() {
        this(null, Clock.systemUTC());
    }

    /** @param maxAge so lange bleiben Läufe, {@code null} ohne Frist */
    public InMemorySearchRunLog(Duration maxAge, Clock clock) {
        this(maxAge, clock, MAX_RUNS);
    }

    InMemorySearchRunLog(Duration maxAge, Clock clock, int capacity) {
        this.maxAge = maxAge;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.capacity = capacity;
    }

    @Override
    public synchronized void record(SearchRun run) {
        Objects.requireNonNull(run, "run");
        if (runs.containsKey(run.id())) {
            throw new IllegalStateException("Suchlauf existiert schon");
        }
        runs.put(run.id(), run);
        if (maxAge != null) {
            deleteBefore(clock.instant().minus(maxAge));
        }
        while (runs.size() > capacity) {
            runs.values().stream().min(Comparator.comparing(SearchRun::startedAt))
                    .ifPresent(oldest -> runs.remove(oldest.id()));
        }
    }

    @Override
    public synchronized Optional<SearchRun> find(SearchRunId id) {
        return Optional.ofNullable(runs.get(Objects.requireNonNull(id, "id")));
    }

    @Override
    public synchronized List<SearchRun> parts(SearchRunId id) {
        Objects.requireNonNull(id, "id");
        return runs.values().stream().filter(run -> id.equals(run.partOf()))
                .sorted(Comparator.comparing(SearchRun::startedAt)).limit(MAX_PARTS).toList();
    }

    @Override
    public synchronized List<SearchRun> recent(String owner, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit muss zwischen 1 und " + MAX_LIMIT + " liegen");
        }
        List<SearchRun> all = new ArrayList<>(runs.values());
        return all.stream().filter(run -> owner == null || owner.equals(run.owner()))
                .sorted(Comparator.comparing(SearchRun::startedAt).reversed()).limit(limit).toList();
    }

    @Override
    public synchronized int deleteBefore(Instant before) {
        Objects.requireNonNull(before, "before");
        int size = runs.size();
        runs.values().removeIf(run -> run.startedAt().isBefore(before));
        return size - runs.size();
    }
}

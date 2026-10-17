package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** Test-Fake der Ablage. */
public final class InMemoryEnrichmentStore implements EnrichmentStore {

    private final Map<PageKey, TreeMap<String, Enrichment>> results = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(Enrichment enrichment) {
        results.computeIfAbsent(enrichment.key(), key -> new TreeMap<>()).put(enrichment.processorId(), enrichment);
    }

    @Override
    public synchronized List<Enrichment> find(PageKey key) {
        TreeMap<String, Enrichment> found = results.get(key);
        return found == null ? List.of() : List.copyOf(new ArrayList<>(found.values()));
    }

    @Override
    public synchronized void removeAll(PageKey key) {
        results.remove(key);
    }
}

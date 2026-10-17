package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PageSearchIndex} im Speicher, als Fake für Suche und API und als Referenz des Vertrags für den Lucene-Index:
 * Treffer nach {@link PageSearchQuery#matches} und {@link SearchSort#order()}; die Position ist die pageId.
 */
public final class InMemorySearchIndex implements PageSearchIndex {

    /** Ein indexierter Eintrag: Kopfdaten einer Seite und die Cache-Generation ihres Space. */
    public record Entry(ListedPage page, CacheGeneration generation) {
    }

    private record Source(SearchIndexState state, List<Entry> entries) {
    }

    private final Map<String, Source> sources = new ConcurrentHashMap<>();

    /** Ersetzt den Index einer Quelle durch eine neue Generation mit genau diesen Einträgen. */
    public void publish(String sourceId, SearchIndexState state, List<Entry> entries) {
        sources.put(sourceId, new Source(state, List.copyOf(entries)));
    }

    public void clear() {
        sources.clear();
    }

    @Override
    public Optional<SearchIndexState> state(String sourceId) {
        return Optional.ofNullable(sources.get(sourceId)).map(Source::state);
    }

    @Override
    public List<SearchHit> search(PageSearchQuery query, String generation, String afterPosition, int limit) {
        Source source = sources.get(query.sourceId());
        if (source == null || !source.state().generation().equals(generation)) {
            throw new CacheGenerationChangedException("Indexgeneration " + generation + " nicht mehr lesbar");
        }
        List<Entry> sorted = source.entries().stream().filter(entry -> query.matches(entry.page()))
                .sorted((left, right) -> query.sort().order().compare(left.page(), right.page())).toList();
        int start = 0;
        if (afterPosition != null) {
            while (start < sorted.size() && !sorted.get(start).page().pageId().equals(afterPosition)) {
                start++;
            }
            start++;
        }
        return sorted.stream().skip(start).limit(limit)
                .map(entry -> new SearchHit(entry.page(), entry.generation(), entry.page().pageId())).toList();
    }

    // Testdaten

    public static Entry entry(PageSnapshot snapshot, CacheGeneration generation) {
        return new Entry(ListedPage.of(snapshot.header(), snapshot), generation);
    }
}

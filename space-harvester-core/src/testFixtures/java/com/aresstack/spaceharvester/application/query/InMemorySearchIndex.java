package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link PageSearchIndex} im Speicher, als Fake für Suche und API und als Referenz des Vertrags für den Lucene-Index:
 * Treffer nach {@link PageSearchQuery#matches} und {@link SearchSort#order()} über alle Quellen der Anfrage; die
 * Position ist {@code sourceId/pageId}.
 */
public final class InMemorySearchIndex implements PageSearchIndex {

    /** Ein indexierter Eintrag: Kopfdaten einer Seite und die Cache-Generation ihres Space. */
    public record Entry(ListedPage page, CacheGeneration generation) {
    }

    private record Source(SearchIndexState state, List<Entry> entries) {
    }

    private final Map<String, Source> sources = new ConcurrentHashMap<>();
    private final AtomicInteger searches = new AtomicInteger();

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

    /** Wie im Lucene-Index ein Stand aus Quellen und Generationen der indexierten Quellen der Menge. */
    @Override
    public Optional<SearchIndexState> state(Set<String> sourceIds) {
        StringBuilder generation = new StringBuilder("g");
        Instant committedAt = null;
        for (String sourceId : new TreeSet<>(sourceIds)) {
            Source source = sources.get(sourceId);
            if (source != null) {
                generation.append('[').append(sourceId).append('=').append(source.state().generation()).append(']');
                Instant at = source.state().committedAt();
                committedAt = committedAt == null || at.isAfter(committedAt) ? at : committedAt;
            }
        }
        return committedAt == null ? Optional.empty()
                : Optional.of(new SearchIndexState(generation.toString(), committedAt));
    }

    @Override
    public List<SearchHit> search(PageSearchQuery query, Set<String> sourceIds, String generation,
                                  String afterPosition, int limit) {
        boolean single = sourceIds.size() == 1
                && state(sourceIds.iterator().next()).map(SearchIndexState::generation).orElse("").equals(generation);
        if (!single && !state(sourceIds).map(SearchIndexState::generation).orElse("").equals(generation)) {
            throw new CacheGenerationChangedException("Indexgeneration " + generation + " nicht mehr lesbar");
        }
        searches.incrementAndGet();
        List<Entry> sorted = sourceIds.stream().map(sources::get).filter(Objects::nonNull)
                .flatMap(source -> source.entries().stream()).filter(entry -> query.matches(entry.page()))
                .sorted((left, right) -> query.sort().order().compare(left.page(), right.page())).toList();
        int start = 0;
        if (afterPosition != null) {
            while (start < sorted.size() && !position(sorted.get(start)).equals(afterPosition)) {
                start++;
            }
            start++;
        }
        return sorted.stream().skip(start).limit(limit)
                .map(entry -> new SearchHit(entry.page(), entry.generation(), position(entry))).toList();
    }

    /** Wie viele Abfragen den Index erreicht haben, z. B. um „eine Abfrage statt einer je Quelle“ zu prüfen. */
    public int searches() {
        return searches.get();
    }

    /** Position eines Treffers: die pageId, global mit der Quelle davor. */
    private static String position(Entry entry) {
        return entry.page().header().key().sourceId() + "/" + entry.page().pageId();
    }

    // Testdaten

    public static Entry entry(PageSnapshot snapshot, CacheGeneration generation) {
        return new Entry(ListedPage.of(snapshot.header(), snapshot), generation);
    }
}

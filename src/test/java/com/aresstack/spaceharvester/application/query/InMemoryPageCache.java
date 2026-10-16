package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PageCacheReader} im Speicher, als Fake für die Query-API und als Referenz des Vertrags für den Lucene-Cache:
 * je Quelle und Space eine aktuelle Generation mit ihren Snapshots.
 */
public final class InMemoryPageCache implements PageCacheReader {

    private record Space(CacheGeneration generation, NavigableMap<String, PageSnapshot> pages) {
    }

    private final Map<String, Space> spaces = new ConcurrentHashMap<>();

    /** Ersetzt den Stand eines Space durch eine neue Generation mit genau diesen Seiten. */
    public void publish(String sourceId, String spaceKey, CacheGeneration generation, List<PageSnapshot> pages) {
        NavigableMap<String, PageSnapshot> byId = new TreeMap<>();
        for (PageSnapshot page : pages) {
            if (!page.key().sourceId().equals(sourceId) || !page.spaceKey().equals(spaceKey)) {
                throw new IllegalArgumentException("Seite gehört nicht zu " + sourceId + "/" + spaceKey);
            }
            byId.put(page.key().pageId(), page);
        }
        spaces.put(sourceId + "\n" + spaceKey, new Space(generation, byId));
    }

    @Override
    public Optional<CacheGeneration> currentGeneration(String sourceId, String spaceKey) {
        return Optional.ofNullable(spaces.get(sourceId + "\n" + spaceKey)).map(Space::generation);
    }

    @Override
    public List<PageHeader> headers(String sourceId, String spaceKey, String generationId, String afterPageId,
                                    int limit) {
        Space space = spaces.get(sourceId + "\n" + spaceKey);
        if (space == null || !space.generation().id().equals(generationId)) {
            throw new CacheGenerationChangedException("Generation " + generationId + " nicht mehr lesbar");
        }
        NavigableMap<String, PageSnapshot> rest = afterPageId == null ? space.pages()
                : space.pages().tailMap(afterPageId, false);
        return rest.values().stream().limit(limit).map(PageHeader::of).toList();
    }

    @Override
    public Optional<CachedPage> page(PageKey key) {
        return spaces.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(key.sourceId() + "\n"))
                .flatMap(entry -> Optional.ofNullable(entry.getValue().pages().get(key.pageId()))
                        .map(page -> new CachedPage(page, entry.getValue().generation())).stream())
                .findFirst();
    }

    // Testdaten

    public static CacheGeneration confirmed(String id) {
        return new CacheGeneration(id, CacheState.CONFIRMED, Instant.parse("2026-10-16T20:00:00Z"),
                Instant.parse("2026-10-16T20:05:00Z"));
    }

    public static PageSnapshot page(String sourceId, String spaceKey, String pageId, String parentPageId) {
        return page(sourceId, spaceKey, pageId, parentPageId,
                ("{\"id\":\"" + pageId + "\",\"type\":\"page\"}").getBytes(StandardCharsets.UTF_8));
    }

    public static PageSnapshot page(String sourceId, String spaceKey, String pageId, String parentPageId,
                                    byte[] payload) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki.example.org/page/" + pageId,
                "Seite " + pageId, spaceKey, parentPageId, "3", Instant.parse("2026-10-15T10:30:00Z"), null,
                PageMetadata.sorted(Map.of("confluence.labels", "api")));
        return PageSnapshot.of(sourceId, metadata, new NativePayload(payload, "application/json",
                "confluence-rest-content+json;expand=body.storage"), Instant.parse("2026-10-16T20:00:00Z"));
    }
}

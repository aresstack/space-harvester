package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PageCacheReader} im Speicher, als Fake für die Query-API und als Referenz des Vertrags für den Lucene-Cache:
 * je Quelle und Space eine aktuelle Generation mit aktuellen Kopfdaten und Snapshots.
 */
public final class InMemoryPageCache implements PageCacheReader {

    private record Space(CacheGeneration generation, NavigableMap<String, PageHeader> headers,
                         Map<String, PageSnapshot> snapshots) {
    }

    private final Map<String, Space> spaces = new ConcurrentHashMap<>();

    /** Ersetzt den Stand eines Space durch eine neue Generation mit genau diesen Seiten und ihren Kopfdaten. */
    public void publish(String sourceId, String spaceKey, CacheGeneration generation, List<PageSnapshot> pages) {
        Space space = new Space(generation, new TreeMap<>(), new HashMap<>());
        for (PageSnapshot page : pages) {
            requireSpace(sourceId, spaceKey, page.header());
            space.headers().put(page.key().pageId(), page.header());
            space.snapshots().put(page.key().pageId(), page);
        }
        spaces.put(sourceId + "\n" + spaceKey, space);
    }

    /** Leert den Cache, etwa zwischen Tests mit gemeinsamem Spring-Kontext. */
    public void clear() {
        spaces.clear();
    }

    /** Neue Kopfdaten ohne neue Payload, wie sie die Synchronisierung schreibt; auch für Seiten ohne Payload. */
    public void updateHeader(String spaceKey, PageHeader header) {
        requireSpace(header.key().sourceId(), spaceKey, header);
        spaces.get(header.key().sourceId() + "\n" + spaceKey).headers().put(header.key().pageId(), header);
    }

    private static void requireSpace(String sourceId, String spaceKey, PageHeader header) {
        if (!header.key().sourceId().equals(sourceId) || !header.metadata().partitionKey().equals(spaceKey)) {
            throw new IllegalArgumentException("Seite gehört nicht zu " + sourceId + "/" + spaceKey);
        }
    }

    @Override
    public Optional<CacheGeneration> currentGeneration(String sourceId, String spaceKey) {
        return Optional.ofNullable(spaces.get(sourceId + "\n" + spaceKey)).map(Space::generation);
    }

    @Override
    public List<ListedPage> pages(String sourceId, String spaceKey, String generationId, String afterPageId,
                                  int limit) {
        Space space = spaces.get(sourceId + "\n" + spaceKey);
        if (space == null || !space.generation().id().equals(generationId)) {
            throw new CacheGenerationChangedException("Generation " + generationId + " nicht mehr lesbar");
        }
        NavigableMap<String, PageHeader> rest = afterPageId == null ? space.headers()
                : space.headers().tailMap(afterPageId, false);
        return rest.values().stream().limit(limit)
                .map(header -> ListedPage.of(header, space.snapshots().get(header.key().pageId())))
                .toList();
    }

    @Override
    public Optional<CachedPage> page(PageKey key) {
        return spaces.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(key.sourceId() + "\n"))
                .map(Map.Entry::getValue)
                .filter(space -> space.snapshots().containsKey(key.pageId()))
                .map(space -> new CachedPage(space.headers().get(key.pageId()), space.snapshots().get(key.pageId()),
                        space.generation()))
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
                "Seite " + pageId, spaceKey, parentPageId, "3", Instant.parse("2026-10-15T10:30:00Z"),
                URI.create("https://wiki.example.org/pages/viewpage.action?pageId=" + pageId),
                PageMetadata.sorted(Map.of("confluence.labels", "api")));
        return PageSnapshot.of(sourceId, metadata, new NativePayload(payload, "application/json",
                "confluence-rest-content+json;expand=body.storage"), Instant.parse("2026-10-16T20:00:00Z"));
    }
}

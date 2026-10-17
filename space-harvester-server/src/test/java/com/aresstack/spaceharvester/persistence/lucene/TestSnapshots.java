package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sync.BaselineEntry;
import com.aresstack.spaceharvester.application.sync.SyncBaseline;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/** Snapshots für die Cache-Tests, ohne echte Confluence-Daten. */
final class TestSnapshots {

    static final Instant RETRIEVED = Instant.parse("2026-10-16T20:00:00Z");
    static final Instant CONFIRMED = Instant.parse("2026-10-16T20:30:00Z");

    private TestSnapshots() {
    }

    static PageSnapshot page(String sourceId, String pageId, String spaceKey, String parentId, String version,
                             byte[] payload) {
        return page(sourceId, pageId, spaceKey, parentId, version, payload, RETRIEVED);
    }

    static PageSnapshot page(String sourceId, String pageId, String spaceKey, String parentId, String version,
                             byte[] payload, Instant retrievedAt) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki.example.org/page/" + pageId,
                "Seite " + pageId, spaceKey, parentId, version, Instant.parse("2026-10-15T10:30:00.123Z"),
                URI.create("https://wiki.example.org/pages/viewpage.action?pageId=" + pageId),
                PageMetadata.sorted(Map.of("confluence.labels", "api,intern", "confluence.status", "current")));
        return PageSnapshot.of(sourceId, metadata, new NativePayload(payload, "application/json",
                "confluence-rest-content+json;expand=body.storage,version"), retrievedAt);
    }

    /** Eine Generation, in der die Seiten mit dem Stand ihrer Snapshots stehen, ohne veraltete Seiten. */
    static SyncBaseline baseline(String sourceId, String spaceKey, long generation, PageSnapshot... pages) {
        SortedMap<String, BaselineEntry> entries = new TreeMap<>();
        for (PageSnapshot page : pages) {
            entries.put(page.key().pageId(), new BaselineEntry(page.key().pageId(), page.spaceKey(),
                    page.parentPageId(), page.revision(), page.metadataHash(), page.attributesHash()));
        }
        return new SyncBaseline(sourceId, spaceKey, generation, CONFIRMED, entries, new TreeSet<>());
    }

    static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

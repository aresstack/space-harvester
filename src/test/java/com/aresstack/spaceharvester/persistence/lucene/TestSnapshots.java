package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/** Snapshots für die Cache-Tests, ohne echte Confluence-Daten. */
final class TestSnapshots {

    static final Instant RETRIEVED = Instant.parse("2026-10-16T20:00:00Z");

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

    static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

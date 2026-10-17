package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/** Seiten für die Enrichment-Tests, ohne echte Confluence-Daten. */
public final class EnrichmentTestPages {

    public static final Instant RETRIEVED = Instant.parse("2026-10-16T20:00:00Z");

    private EnrichmentTestPages() {
    }

    /** Eine Seite, deren Payload ein Text-Testformat ist ({@code text/plain}). */
    public static PageSnapshot text(String sourceId, String pageId, String version, String text) {
        return page(sourceId, pageId, version, text.getBytes(StandardCharsets.UTF_8), "test-text");
    }

    public static PageSnapshot page(String sourceId, String pageId, String version, byte[] payload,
                                    String nativeFormat) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki.example.org/page/" + pageId,
                "Seite " + pageId, "DEV", null, version, Instant.parse("2026-10-15T10:30:00Z"),
                URI.create("https://wiki.example.org/pages/viewpage.action?pageId=" + pageId),
                PageMetadata.sorted(Map.of()));
        return PageSnapshot.of(sourceId, metadata, new NativePayload(payload, "application/json", nativeFormat),
                RETRIEVED);
    }
}

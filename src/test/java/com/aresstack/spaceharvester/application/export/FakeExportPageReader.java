package com.aresstack.spaceharvester.application.export;

import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Test-Fake für den Cache-Leser: eine Partition mit fester Generation, Seiten und nicht lieferbaren Seiten in
 * Lesereihenfolge, ausgeliefert in Stapeln mit numerischem Cursor. Erzeugt Seiten bei Bedarf, damit große Spaces nicht
 * im Speicher liegen.
 */
public final class FakeExportPageReader implements ExportPageReader {

    public static final Instant RETRIEVED = Instant.parse("2026-10-16T10:00:00Z");

    private final ExportScope scope;
    private final int pageCount;
    private final List<Entry> entries;
    private int batches;

    private FakeExportPageReader(ExportScope scope, int pageCount, List<Entry> entries) {
        this.scope = scope;
        this.pageCount = pageCount;
        this.entries = entries;
    }

    /** Seiten nach {@code page(...)}/{@code failure(...)}. */
    public static FakeExportPageReader of(CacheState state, Entry... entries) {
        return new FakeExportPageReader(new ExportScope("confluence", "DEV", "g1", state), -1, List.of(entries));
    }

    /** {@code pages} erzeugte Seiten; Seite {@code i} hängt an Seite {@code i / 10} (Seite 0 ist Wurzel). */
    public static FakeExportPageReader generated(int pages) {
        return new FakeExportPageReader(new ExportScope("confluence", "DEV", "g1", CacheState.CONFIRMED), pages,
                List.of());
    }

    public static Entry page(String pageId, String parentPageId, String json) {
        return new Entry(exportPage(pageId, parentPageId, json.getBytes(StandardCharsets.UTF_8)), null);
    }

    public static Entry failure(String pageId) {
        return new Entry(null, new PageFailure(pageId, "PAYLOAD_MISSING", "Payload von Seite " + pageId + " fehlt"));
    }

    public static ExportPage exportPage(String pageId, String parentPageId, byte[] payload) {
        TreeMap<String, String> attributes = new TreeMap<>();
        attributes.put("labels", "a,b");
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki.example/page/" + pageId, "Seite " + pageId,
                "DEV", parentPageId, "3", RETRIEVED, null, attributes);
        return new ExportPage("confluence", metadata, "sha256:meta-" + pageId, "sha256:attr-" + pageId, payload,
                "application/json", "confluence-rest-content+json", RETRIEVED);
    }

    public int batches() {
        return batches;
    }

    @Override
    public Optional<ExportScope> scope(String sourceId, String partitionKey) {
        return scope.sourceId().equals(sourceId) && scope.partitionKey().equals(partitionKey)
                ? Optional.of(scope) : Optional.empty();
    }

    @Override
    public ExportPageBatch pages(ExportScope requested, String cursor, int limit) {
        if (!requested.equals(scope)) {
            throw new IllegalArgumentException("fremde Generation");
        }
        batches++;
        int from = cursor == null ? 0 : Integer.parseInt(cursor);
        int size = pageCount >= 0 ? pageCount : entries.size();
        int to = Math.min(size, from + limit);
        List<ExportPage> pages = new ArrayList<>();
        List<PageFailure> failures = new ArrayList<>();
        for (int i = from; i < to; i++) {
            Entry entry = pageCount >= 0 ? generatedEntry(i) : entries.get(i);
            if (entry.page() != null) {
                pages.add(entry.page());
            } else {
                failures.add(entry.failure());
            }
        }
        return new ExportPageBatch(pages, failures, to < size ? String.valueOf(to) : null);
    }

    private static Entry generatedEntry(int i) {
        String json = "{\"id\":\"" + i + "\",\"type\":\"page\",\"body\":{\"storage\":{\"value\":\"<p>Seite " + i
                + "</p>\",\"representation\":\"storage\"}}}";
        return new Entry(exportPage(String.valueOf(i), i == 0 ? null : String.valueOf(i / 10),
                json.getBytes(StandardCharsets.UTF_8)), null);
    }

    /** Genau eins von beiden ist gesetzt. */
    public record Entry(ExportPage page, PageFailure failure) {
    }
}

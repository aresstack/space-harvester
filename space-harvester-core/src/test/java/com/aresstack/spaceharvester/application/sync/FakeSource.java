package com.aresstack.spaceharvester.application.sync;

import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Veränderbare Quelle für Sync-Tests: Seiten je ID, Cursor = Offset, Fehler je Aufruf einstellbar. */
class FakeSource implements HarvestSource {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC);

    private final Map<String, PageMetadata> pages = new LinkedHashMap<>();
    private final Map<String, SourceAccessException.Reason> singlePageFailures = new HashMap<>();
    private int failListingAtCall = -1;
    /** Ersetzt den Stapel vor der Auslieferung, um Widersprüche zu simulieren. */
    UnaryOperator<PageMetadataBatch> tamper = UnaryOperator.identity();
    private int listingCalls;
    private boolean confirmsDeletion;
    private final java.util.Set<String> trash = new java.util.LinkedHashSet<>();
    private boolean listsChanges;
    /** Liest Änderungsereignisse als Zeilen {@code ART:Seite:Space:Version}. */
    boolean readsChangeEvents;
    /** Anzahl der Einzelabfragen. */
    int singlePageCalls;
    /** Fenster der letzten Änderungssuche. */
    java.time.Duration lastWithin;

    @Override
    public String id() {
        return "wiki";
    }

    @Override
    public String type() {
        return "fake";
    }

    @Override
    public String title() {
        return "Wiki";
    }

    @Override
    public List<Partition> partitions() {
        return List.of();
    }

    @Override
    public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
        if (listingCalls++ == failListingAtCall) {
            throw new SourceAccessException(id(), SourceAccessException.Reason.UNAVAILABLE, "Zeitüberschreitung",
                    null);
        }
        List<PageMetadata> inSpace = pages.values().stream()
                .filter(page -> page.partitionKey().equals(partitionKey)).toList();
        int offset = cursor == null ? 0 : Integer.parseInt(cursor);
        int end = Math.min(offset + limit, inSpace.size());
        String next = end < inSpace.size() ? Integer.toString(end) : null;
        return tamper.apply(new PageMetadataBatch(new ArrayList<>(inSpace.subList(offset, end)), next));
    }

    @Override
    public PageMetadata pageMetadata(String pageId) {
        singlePageCalls++;
        SourceAccessException.Reason failure = singlePageFailures.get(pageId);
        if (failure != null) {
            throw new SourceAccessException(id(), failure, "Einzelabfrage fehlgeschlagen", null);
        }
        PageMetadata page = pages.get(pageId);
        if (page == null) {
            throw new SourceAccessException(id(), SourceAccessException.Reason.NOT_FOUND, "Seite nicht gefunden",
                    null);
        }
        return page;
    }

    @Override
    public boolean readsChangeEvents() {
        return readsChangeEvents;
    }

    @Override
    public List<com.aresstack.spaceharvester.application.sources.ChangeEvent> changeEvents(byte[] body,
                                                                                         String eventKey) {
        String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        if (!text.contains(":")) {
            throw new com.aresstack.spaceharvester.application.sources.UnreadableChangeEventException("kein Ereignis");
        }
        List<com.aresstack.spaceharvester.application.sources.ChangeEvent> events = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] parts = line.split(":", -1);
            events.add(new com.aresstack.spaceharvester.application.sources.ChangeEvent(
                    com.aresstack.spaceharvester.application.sources.ChangeEvent.Kind.valueOf(parts[0]), parts[1],
                    parts[2].isEmpty() ? null : parts[2], parts[3].isEmpty() ? null : parts[3]));
        }
        return events;
    }

    @Override
    public boolean listsChanges() {
        return listsChanges;
    }

    @Override
    public PageMetadataBatch changedPageMetadata(String partitionKey, java.time.Duration within, String cursor,
                                                 int limit) {
        requireChanges();
        lastWithin = within;
        Instant from = CLOCK.instant().minus(within);
        return new PageMetadataBatch(pages.values().stream()
                .filter(page -> page.partitionKey().equals(partitionKey) && !page.modifiedAt().isBefore(from))
                .sorted(java.util.Comparator.comparing(PageMetadata::modifiedAt))
                .toList(), null);
    }

    @Override
    public PageMetadataBatch removedPageMetadata(String partitionKey, String cursor, int limit) {
        requireChanges();
        return new PageMetadataBatch(trash.stream()
                .map(pageId -> page(pageId, partitionKey, null, "1", null)).toList(), null);
    }

    private void requireChanges() {
        if (!listsChanges) {
            throw new SourceAccessException(id(), SourceAccessException.Reason.UNSUPPORTED, "keine Änderungen", null);
        }
        if (listingCalls++ == failListingAtCall) {
            throw new SourceAccessException(id(), SourceAccessException.Reason.UNAVAILABLE, "Zeitüberschreitung",
                    null);
        }
    }

    /** Meldet ab jetzt Änderungen und Papierkorb. */
    FakeSource listingChanges() {
        listsChanges = true;
        return this;
    }

    /** Neue Version, geändert {@code minutesAgo} Minuten vor {@link #CLOCK}. */
    FakeSource change(String pageId, String version, long minutesAgo) {
        PageMetadata old = pages.get(pageId);
        pages.put(pageId, new PageMetadata(pageId, old.resourceId(), old.title(), old.partitionKey(),
                old.parentPageId(), version, CLOCK.instant().minusSeconds(60 * minutesAgo), old.url(),
                old.attributes()));
        return this;
    }

    /** In den Papierkorb: aus dem Space verschwunden, Einzelabfrage „nicht gefunden“. */
    FakeSource trash(String pageId) {
        pages.remove(pageId);
        trash.add(pageId);
        return this;
    }

    FakeSource put(String pageId, String space, String parent, String version, String labels) {
        pages.put(pageId, page(pageId, space, parent, version, labels));
        return this;
    }

    @Override
    public boolean confirmsDeletion() {
        return confirmsDeletion;
    }

    /** „Nicht gefunden“ bestätigt ab jetzt eine Löschung, wie bei lokalen Dateien (ADR 0014). */
    FakeSource confirmingDeletion() {
        confirmsDeletion = true;
        return this;
    }

    FakeSource remove(String pageId) {
        pages.remove(pageId);
        return this;
    }

    /** Der wievielte Listing-Aufruf ab jetzt (0-basiert) fehlschlägt; -1 = keiner. */
    FakeSource failListingAtCall(int call) {
        failListingAtCall = call;
        listingCalls = 0;
        return this;
    }

    FakeSource failSinglePage(String pageId, SourceAccessException.Reason reason) {
        singlePageFailures.put(pageId, reason);
        return this;
    }

    SourceQueries queries() {
        return new SourceQueries(new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(FakeSource.this);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return id().equals(sourceId) ? Optional.of(FakeSource.this) : Optional.empty();
            }
        }, CLOCK);
    }

    static PageMetadata page(String pageId, String space, String parent, String version, String labels) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("confluence.type", "page");
        if (labels != null) {
            attributes.put("confluence.labels", labels);
        }
        return new PageMetadata(pageId, "confluence:wiki.example.org/page/" + pageId, "Seite " + pageId, space,
                parent, version, Instant.parse("2026-10-01T08:00:00Z"),
                URI.create("https://wiki.example.org/pages/" + pageId), PageMetadata.sorted(attributes));
    }
}

package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.observe.Coalescer;
import com.aresstack.spaceharvester.application.observe.Listeners;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Abfragen des Änderungsprotokolls (ADR 0030, Abschnitt 3 und 4): Updates je Quelle, Änderungsbericht eines Updates und
 * rohe Zeilen mit Suche. Jede Zeile hat die Leserechte ihrer Seite: Was der Aufrufer an den Kopfdaten der Seite nicht
 * sehen darf, fehlt in Zeilen, Bericht und Zählern. Neue Zeilen und Marken meldet {@link #subscribe} (ADR 0027).
 */
public final class ChangeLog implements AutoCloseable {

    /** Reihenfolge, in der eine Seite mit mehreren Änderungen im Bericht eingeordnet wird. */
    private static final List<ChangeKind> PRIORITY = List.of(ChangeKind.CONTENT_CHANGED, ChangeKind.MOVED_OUT,
            ChangeKind.RENAMED, ChangeKind.MOVED, ChangeKind.LABELS_CHANGED);
    private static final int MAX_PATH_DEPTH = 50;

    private final ChangeLogStore store;
    private final AccessGuard access;
    private final Function<PageKey, Optional<PageHeader>> headers;
    private final Supplier<? extends Collection<String>> sources;
    private final Listeners<ChangeLogListener> listeners = new Listeners<>("Änderungsprotokoll");
    private final Coalescer<String, String> coalescer;

    /**
     * @param headers jüngste Kopfdaten einer Seite im Cache, für Titel, Pfad und Verweis im Bericht
     * @param sources die IDs der konfigurierten Quellen
     */
    public ChangeLog(ChangeLogStore store, AccessGuard access, Function<PageKey, Optional<PageHeader>> headers,
                     Supplier<? extends Collection<String>> sources) {
        this.store = Objects.requireNonNull(store, "store");
        this.access = Objects.requireNonNull(access, "access");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.coalescer = new Coalescer<>("Änderungsprotokoll",
                (sourceId, ignored) -> listeners.deliver(sourceId, listener -> listener.changed(sourceId)));
        store.changeLogRecorded(coalescer::mark);
    }

    /**
     * Meldet neue Zeilen und Marken einer Quelle, oder aller Quellen, die der Aufrufer sehen darf (ADR 0027).
     *
     * @param sourceId eine Quelle, {@code null} für alle
     */
    public Subscription subscribe(AccessContext caller, String sourceId, ChangeLogListener listener) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(listener, "listener");
        if (sourceId != null) {
            requireReadable(caller, sourceId);
        }
        return listeners.add(listener, subject -> sourceId == null
                ? access.permits(caller, ResourceScope.source((String) subject), AccessKind.METADATA)
                : sourceId.equals(subject));
    }

    /**
     * Die Updates einer Quelle, neuestes zuerst; das offene nach der letzten Marke vorn, wenn es Änderungen hat, die
     * der Aufrufer sehen darf.
     *
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne Freigabe der Quelle
     */
    public List<ChangeUpdate> updates(AccessContext caller, String sourceId) {
        Objects.requireNonNull(caller, "caller");
        requireReadable(caller, sourceId);
        Visibility visible = new Visibility(caller);
        List<ChangeEntry> entries = store.changeEntries(ChangeQuery.source(sourceId), visible::view, 0,
                Integer.MAX_VALUE).entries();
        List<ChangeUpdate> updates = new ArrayList<>();
        int next = 0;
        for (Span span : spans(sourceId)) {
            // Zeilen und Marken sind beide nach Zeit geordnet: jede Zeile gehört zum ersten Update, das nach ihr endet.
            int first = next;
            while (next < entries.size() && (span.to() == null || !entries.get(next).at().isAfter(span.to()))) {
                next++;
            }
            List<ChangeEntry> rows = entries.subList(first, next);
            if (span.mark() == null && rows.isEmpty()) {
                continue;
            }
            updates.add(consolidate(span, rows).update());
        }
        return updates.reversed();
    }

    /**
     * Der Änderungsbericht eines Updates: die Seiten nach Art und Titel, mit Pfad und Verweis; bei der Erstbefüllung
     * nur Zähler.
     *
     * @param update {@link ChangeUpdate#id()} oder {@link ChangeUpdate#OPEN}
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws UnknownUpdateException wenn die Quelle kein solches Update hat
     */
    public ChangeReport report(AccessContext caller, String sourceId, String update) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(update, "update");
        requireReadable(caller, sourceId);
        Span span = spans(sourceId).stream().filter(candidate -> candidate.id().equals(update)).findFirst()
                .orElseThrow(() -> new UnknownUpdateException(sourceId));
        Visibility visible = new Visibility(caller);
        List<ChangeEntry> rows = store.changeEntries(ChangeQuery.span(sourceId, span.from(), span.to()),
                visible::view, 0, Integer.MAX_VALUE).entries();
        Consolidated consolidated = consolidate(span, rows);
        if (consolidated.update().initial()) {
            return new ChangeReport(consolidated.update(), List.of());
        }
        List<ReportedPage> pages = new ArrayList<>();
        for (ReportedPage page : consolidated.pages()) {
            pages.add(withDirectory(page, visible));
        }
        pages.sort(Comparator.comparing(ReportedPage::kind)
                .thenComparing(page -> page.title() == null ? "" : page.title(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ReportedPage::pageId));
        return new ChangeReport(consolidated.update(), pages);
    }

    /** Rohe Zeilen für die Einsicht, neueste zuerst, seitenweise; nur Zeilen, die der Aufrufer sehen darf. */
    public ChangeEntries entries(AccessContext caller, ChangeFilter filter) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(filter, "filter");
        Collection<String> wanted;
        if (filter.sourceId() != null) {
            requireReadable(caller, filter.sourceId());
            wanted = List.of(filter.sourceId());
        } else {
            wanted = sources.get().stream()
                    .filter(sourceId -> access.permits(caller, ResourceScope.source(sourceId), AccessKind.METADATA))
                    .toList();
        }
        if (wanted.isEmpty()) {
            return new ChangeEntries(List.of(), 0, filter.offset(), filter.limit());
        }
        Visibility visible = new Visibility(caller);
        ChangeQuery query = filter.query(wanted);
        // Eine gekürzte Zeile muss auch ohne die verborgene Seite passen (Space und Titel nur der sichtbaren Seite).
        return store.changeEntries(query, entry -> {
            ChangeEntry seen = visible.view(entry);
            return seen == null || seen == entry || query.matches(seen) ? seen : null;
        }, filter.offset(), filter.limit());
    }

    @Override
    public void close() {
        coalescer.close();
    }

    // --- Updates -------------------------------------------------------------------------------------------------

    /** Zeitraum eines Updates zwischen zwei Marken. */
    private record Span(String id, String sourceId, Instant from, Instant to, UpdateMark mark, boolean initial) {
    }

    private List<Span> spans(String sourceId) {
        List<UpdateMark> marks = store.marks(sourceId);
        List<Span> spans = new ArrayList<>();
        Instant previous = null;
        for (UpdateMark mark : marks) {
            spans.add(new Span(id(mark.at()), sourceId, previous, mark.at(), mark, mark.initial()));
            previous = mark.at();
        }
        spans.add(new Span(ChangeUpdate.OPEN, sourceId, previous, null, null, marks.isEmpty()));
        return spans;
    }

    private static String id(Instant at) {
        return java.math.BigInteger.valueOf(at.getEpochSecond()).multiply(java.math.BigInteger.valueOf(1_000_000_000L))
                .add(java.math.BigInteger.valueOf(at.getNano())).toString();
    }

    private record Consolidated(ChangeUpdate update, List<ReportedPage> pages) {
    }

    /**
     * Fasst die Zeilen eines Updates je Seite zusammen (ADR 0030, Abschnitt 3): erster alter, letzter neuer Stand; neu
     * und wieder entfernt im selben Update erscheint nicht. Laden ohne Änderung zählt nur als Zahl.
     */
    private static Consolidated consolidate(Span span, List<ChangeEntry> rows) {
        Map<String, List<ChangeEntry>> byPage = new LinkedHashMap<>();
        int loaded = 0;
        int reloaded = 0;
        for (ChangeEntry row : rows) {
            if (row.kind() == ChangeKind.LOADED) {
                loaded++;
            } else if (row.kind() == ChangeKind.RELOADED) {
                reloaded++;
            }
            byPage.computeIfAbsent(row.pageId(), ignored -> new ArrayList<>()).add(row);
        }
        List<ReportedPage> pages = new ArrayList<>();
        Map<ChangeKind, Integer> kinds = new EnumMap<>(ChangeKind.class);
        Map<String, Integer> spaces = new TreeMap<>();
        for (List<ChangeEntry> pageRows : byPage.values()) {
            ReportedPage page = page(pageRows);
            if (page != null) {
                pages.add(page);
                kinds.merge(page.kind(), 1, Integer::sum);
                spaces.merge(page.spaceKey(), 1, Integer::sum);
            }
        }
        ChangeUpdate update = new ChangeUpdate(span.id(), span.sourceId(), span.from(), span.to(), span.mark(),
                span.initial(), kinds, spaces, pages.size(), loaded, reloaded, false);
        return new Consolidated(update, pages);
    }

    private static ReportedPage page(List<ChangeEntry> rows) {
        List<ChangeEntry> changes = rows.stream().filter(row -> row.kind().sourceChange()).toList();
        if (changes.isEmpty()) {
            return null;
        }
        ChangeEntry first = changes.getFirst();
        ChangeEntry last = changes.getLast();
        boolean gone = last.kind() == ChangeKind.REMOVED || last.kind() == ChangeKind.WITHHELD;
        boolean created = first.kind() == ChangeKind.NEW;
        if (created && gone) {
            return null;
        }
        Set<ChangeKind> kinds = new LinkedHashSet<>();
        changes.forEach(row -> kinds.add(row.kind()));
        ChangeKind kind;
        if (gone) {
            kind = last.kind();
        } else if (created) {
            kind = ChangeKind.NEW;
        } else {
            kind = PRIORITY.stream().filter(kinds::contains).findFirst().orElse(last.kind());
        }
        boolean pending = false;
        for (ChangeEntry row : rows) {
            if (row.kind() == ChangeKind.NEW || row.kind() == ChangeKind.CONTENT_CHANGED) {
                pending = row.contentPending();
            } else if (row.kind() == ChangeKind.LOADED || row.kind() == ChangeKind.RELOADED) {
                pending = false;
            }
        }
        String title = last.newTitle() != null ? last.newTitle() : last.oldTitle();
        String space = gone ? last.spaceKey() : last.newSpaceKey() != null ? last.newSpaceKey() : last.spaceKey();
        return new ReportedPage(last.sourceId(), space, last.pageId(), kind, kinds, title, List.of(), null,
                first.oldVersion(), gone ? null : last.newVersion(), first.oldTitle(), gone ? null : last.newTitle(),
                first.oldParentId(), gone ? null : last.newParentId(), first.oldSpaceKey(),
                gone ? null : last.newSpaceKey(), pending && !gone, last.at());
    }

    /** Pfad und Verweis aus dem Cache; Vorfahren, die der Aufrufer nicht sehen darf, fehlen im Pfad. */
    private ReportedPage withDirectory(ReportedPage page, Visibility visible) {
        Optional<PageHeader> header = headers.apply(new PageKey(page.sourceId(), page.pageId()));
        URI url = header.map(found -> found.metadata().url()).orElse(null);
        List<String> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String parent = header.map(found -> found.metadata().parentPageId())
                .orElse(page.newParentId() != null ? page.newParentId() : page.oldParentId());
        while (parent != null && !parent.isEmpty() && path.size() < MAX_PATH_DEPTH && seen.add(parent)) {
            Optional<PageHeader> ancestor = headers.apply(new PageKey(page.sourceId(), parent));
            if (ancestor.isEmpty()) {
                break;
            }
            PageHeader found = ancestor.get();
            if (visible.page(page.sourceId(), found.metadata().partitionKey(), parent)) {
                path.addFirst(found.metadata().title());
            }
            parent = found.metadata().parentPageId();
        }
        return new ReportedPage(page.sourceId(), page.spaceKey(), page.pageId(), page.kind(), page.kinds(),
                page.title(), path, url, page.oldVersion(), page.newVersion(), page.oldTitle(), page.newTitle(),
                page.oldParentId(), page.newParentId(), page.oldSpaceKey(), page.newSpaceKey(),
                page.contentPending(), page.at());
    }

    // --- Rechte --------------------------------------------------------------------------------------------------

    /** Leserechte je Seite, je Aufruf zwischengespeichert. */
    private final class Visibility {
        private final AccessContext caller;
        private final Map<String, Boolean> decided = new HashMap<>();

        Visibility(AccessContext caller) {
            this.caller = caller;
        }

        /**
         * Die Zeile, wie der Aufrufer sie sehen darf, oder {@code null}. Wandert eine Seite zwischen Spaces, zählen
         * beide Seiten der Zeile: Ohne Recht am neuen Space bleibt nur {@link ChangeKind#MOVED_OUT} im alten Space,
         * ohne neuen Titel, neue Elternseite und neuen Space; ohne Recht am alten Space fehlen alter Titel, alte
         * Elternseite und alter Space.
         */
        ChangeEntry view(ChangeEntry entry) {
            String old = entry.oldSpaceKey();
            boolean moved = old != null && !old.equals(entry.spaceKey());
            if (!page(entry.sourceId(), entry.spaceKey(), entry.pageId())) {
                if (!moved || entry.kind() != ChangeKind.MOVED_OUT || !page(entry.sourceId(), old, entry.pageId())) {
                    return null;
                }
                return new ChangeEntry(entry.sourceId(), old, entry.pageId(), entry.at(), entry.kind(),
                        entry.trigger(), entry.oldVersion(), null, entry.oldTitle(), null, entry.oldParentId(), null,
                        old, null, entry.generation(), false);
            }
            if (moved && !page(entry.sourceId(), old, entry.pageId())) {
                return new ChangeEntry(entry.sourceId(), entry.spaceKey(), entry.pageId(), entry.at(), entry.kind(),
                        entry.trigger(), entry.oldVersion(), entry.newVersion(), null, entry.newTitle(), null,
                        entry.newParentId(), null, entry.newSpaceKey(), entry.generation(), entry.contentPending());
            }
            return entry;
        }

        boolean page(String sourceId, String spaceKey, String pageId) {
            return decided.computeIfAbsent(sourceId + '\u001f' + spaceKey + '\u001f' + pageId,
                    ignored -> access.permits(caller, ResourceScope.page(sourceId, spaceKey, pageId),
                            AccessKind.METADATA));
        }
    }

    /** Eine Quelle ohne jede Freigabe gilt wie eine unbekannte (ADR 0030: das Protokoll hat die Leserechte). */
    private void requireReadable(AccessContext caller, String sourceId) {
        requireSource(sourceId);
        access.requireConcealed(caller, ResourceScope.source(sourceId), AccessKind.METADATA);
    }

    private void requireSource(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (!sources.get().contains(sourceId)) {
            throw new UnknownSourceException(sourceId);
        }
    }
}

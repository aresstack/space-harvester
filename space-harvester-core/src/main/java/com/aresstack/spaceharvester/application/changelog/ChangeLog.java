package com.aresstack.spaceharvester.application.changelog;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.observe.Coalescer;
import com.aresstack.spaceharvester.application.observe.Listeners;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
import java.util.function.Predicate;
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
    /** Höchstens so viele Schritte im Unterschied einer Seite. */
    static final int MAX_DIFF_STEPS = 20;
    /** Reihenfolge der Seiten im Bericht: nach Art, dann Titel, dann Kennung. */
    private static final Comparator<ReportedPage> REPORT_ORDER = Comparator.comparing(ReportedPage::kind)
            .thenComparing(page -> page.title() == null ? "" : page.title(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(ReportedPage::pageId);

    private final ChangeLogStore store;
    private final AccessGuard access;
    private final Function<PageKey, Optional<PageHeader>> headers;
    private final Supplier<? extends Collection<String>> sources;
    private final Listeners<ChangeLogListener> listeners = new Listeners<>("Änderungsprotokoll");
    private final Coalescer<String, String> coalescer;
    private final Summaries summaries;
    /** Zeitzone der Tage, wenn je Tag ein Update gilt ({@link #perDay}); {@code null}: jede Marke ein Update. */
    private volatile ZoneId days;
    /** Hält „schon offen?“ und Einreihen von „Zusammenfassen“ zusammen. */
    private final Object summarizing = new Object();
    /** Entscheidungen „Erstbefüllung“ je Quelle, Marke und Seitenzahl; ändern sich erst mit neuen Seiten. */
    private final Map<String, Boolean> inventories = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Was „Zusammenfassen“ braucht (ADR 0030, Abschnitt 5).
     *
     * @param released ob eine Seite an den KI-Dienst darf ({@code AiRelease::mayProcess}, ADR 0028)
     * @param material Lesetext und Space-Namen, damit die Zusammenfassung sagt, worum es geht (Angelo, 21.11.2026)
     * @param planner  Modell des Plan-Schritts vorab (Angelo 22.11.2026: erst planen, dann in kleine Teilaufgaben
     *                 zerlegen), Einstellungszeile „Zusammenfassung – Plan“; {@code null} heißt ohne Plan
     */
    public record Summaries(Jobs jobs, SummaryModel model, Predicate<PageKey> released, Clock clock,
                            SummaryMaterial material, SummaryModel planner) {

        public Summaries {
            Objects.requireNonNull(jobs, "jobs");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(released, "released");
            Objects.requireNonNull(clock, "clock");
            material = material == null ? SummaryMaterial.NONE : material;
        }

        /** Ohne Plan-Schritt. */
        public Summaries(Jobs jobs, SummaryModel model, Predicate<PageKey> released, Clock clock,
                         SummaryMaterial material) {
            this(jobs, model, released, clock, material, null);
        }

        /** Ohne Lesetext und Space-Namen: nur Titel, Pfade und Unterschiede. */
        public Summaries(Jobs jobs, SummaryModel model, Predicate<PageKey> released, Clock clock) {
            this(jobs, model, released, clock, SummaryMaterial.NONE);
        }
    }

    /** Parameter des Auftrags „Zusammenfassen“: das Update. */
    public static final String UPDATE_PARAMETER = "update";
    /** Parameter des Auftrags {@link JobType#UPDATE_SUMMARY}: Space der Ebene, fehlt für die ganze Quelle. */
    public static final String SPACE_PARAMETER = "space";
    /** Parameter eines Unterauftrags von „Zusammenfassen“: die ID des Oberauftrags (Angelo 22.11.2026). */
    public static final String PARENT_PARAMETER = "summary.parent";
    /** Parameter eines Unterauftrags von „Zusammenfassen“: sein Schritt, etwa „Teil 2 von 5“. */
    public static final String STEP_PARAMETER = "summary.step";
    /** So lange darf kein Unterauftrag laufen, bevor der Oberauftrag einen wartenden selbst übernimmt. */
    static final java.time.Duration CHILD_START_GRACE = java.time.Duration.ofSeconds(15);

    /**
     * @param headers jüngste Kopfdaten einer Seite im Cache, für Titel, Pfad und Verweis im Bericht
     * @param sources die IDs der konfigurierten Quellen
     */
    public ChangeLog(ChangeLogStore store, AccessGuard access, Function<PageKey, Optional<PageHeader>> headers,
                     Supplier<? extends Collection<String>> sources) {
        this(store, access, headers, sources, null);
    }

    /**
     * @param summaries was „Zusammenfassen“ braucht; {@code null} ohne, dann gibt es keine Zusammenfassungen
     */
    public ChangeLog(ChangeLogStore store, AccessGuard access, Function<PageKey, Optional<PageHeader>> headers,
                     Supplier<? extends Collection<String>> sources, Summaries summaries) {
        this.summaries = summaries;
        this.store = Objects.requireNonNull(store, "store");
        this.access = Objects.requireNonNull(access, "access");
        this.headers = Objects.requireNonNull(headers, "headers");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.coalescer = new Coalescer<>("Änderungsprotokoll",
                (sourceId, ignored) -> listeners.deliver(sourceId, listener -> listener.changed(sourceId)));
        store.changeLogRecorded(coalescer::mark);
    }

    /**
     * Höchstens ein Update je Tag in {@code zone} (Angelo, 21.11.2026): alle Abgleiche eines Tages bilden ein Update mit
     * einer Zusammenfassung, siehe {@link DailyUpdateMarks}. Ohne Aufruf ist jede Marke ein eigenes Update.
     *
     * @return dieses Protokoll
     */
    public ChangeLog perDay(ZoneId zone) {
        this.days = Objects.requireNonNull(zone, "zone");
        return this;
    }

    /** Die Marken einer Quelle, älteste zuerst; je Tag zusammengelegt, wenn {@link #perDay} gesetzt ist. */
    private List<UpdateMark> marks(String sourceId) {
        List<UpdateMark> stored = store.marks(sourceId);
        ZoneId zone = days;
        return zone == null ? stored : DailyUpdateMarks.perDay(stored, zone);
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
     * Die jüngste Update-Marke der Quelle, ohne Zeilen zu lesen; für Beobachter, die nach jeder Meldung nur wissen
     * wollen, ob ein neues Update abgeschlossen ist (etwa „Nach dem Update Änderungen anzeigen“). Rechte wie
     * {@link #updates}.
     *
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     */
    public Optional<UpdateMark> latestMark(AccessContext caller, String sourceId) {
        return latestMark(caller, sourceId, null);
    }

    /**
     * Wie {@link #latestMark(AccessContext, String)} für die Space-Ebene (ADR 0031): die jüngste Marke, deren Runde den
     * Space abgeglichen hat. Die Marke bleibt eine der Quelle; der Space ist ein Ausschnitt.
     *
     * @param spaceKey ein Space, {@code null} für die ganze Quelle
     */
    public Optional<UpdateMark> latestMark(AccessContext caller, String sourceId, String spaceKey) {
        Objects.requireNonNull(caller, "caller");
        requireReadable(caller, sourceId);
        List<UpdateMark> marks = marks(sourceId);
        for (int i = marks.size() - 1; i >= 0; i--) {
            if (spaceKey == null || marks.get(i).spaces().contains(spaceKey)) {
                return Optional.of(marks.get(i));
            }
        }
        return Optional.empty();
    }

    /**
     * Die Updates einer Quelle, neuestes zuerst; das offene nach der letzten Marke vorn, wenn es Änderungen hat, die
     * der Aufrufer sehen darf.
     *
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws com.aresstack.spaceharvester.application.access.AccessDeniedException ohne Freigabe der Quelle
     */
    public List<ChangeUpdate> updates(AccessContext caller, String sourceId) {
        return updates(caller, sourceId, null);
    }

    /**
     * Die Updates einer Quelle auf der Ebene eines Space (ADR 0031): dieselben Updates von Marke zu Marke, Zähler und
     * Seiten nur aus Zeilen, deren neuer oder alter Space {@code spaceKey} ist. Ein Update ohne Änderungen im Space
     * bleibt in der Liste (Zähler 0), das offene nur mit Änderungen. Zusammenfassungen gibt es hier noch je Quelle.
     *
     * @param spaceKey ein Space, {@code null} für die ganze Quelle
     */
    public List<ChangeUpdate> updates(AccessContext caller, String sourceId, String spaceKey) {
        Objects.requireNonNull(caller, "caller");
        requireReadable(caller, sourceId);
        Visibility visible = new Visibility(caller);
        ChangeQuery query = rows(sourceId, spaceKey, null, null);
        List<ChangeEntry> entries = store.changeEntries(query, entry -> visible.within(entry, query), 0,
                Integer.MAX_VALUE).entries();
        List<ChangeUpdate> updates = new ArrayList<>();
        Set<String> summarized = store.summarizedUpdates(sourceId, spaceKey);
        int next = 0;
        for (Span span : spans(sourceId)) {
            // Zeilen und Marken sind beide nach Zeit geordnet: jede Zeile gehört zum ersten Update, das nach ihr endet.
            int first = next;
            while (next < entries.size() && (span.to() == null || !entries.get(next).at().isAfter(span.to()))) {
                next++;
            }
            List<ChangeEntry> rows = entries.subList(first, next);
            ChangeUpdate update = consolidate(span, rows).update();
            if (span.mark() == null && update.pages() == 0) {
                // Seit der letzten Marke keine Änderung der Quelle: kein offenes Update, auch wenn Inhalte nachgeladen
                // wurden (die zählen nur als Zahl, ADR 0030).
                continue;
            }
            if (summarized.contains(update.id()) && store.summary(sourceId, update.id(), spaceKey)
                    .filter(stored -> sameSpan(stored, span) && valid(stored, visible)).isPresent()) {
                update = update.withSummarized();
            }
            updates.add(update);
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
        return report(caller, sourceId, update, false);
    }

    /**
     * Wie {@link #report(AccessContext, String, String)}; mit {@code withDiffs} trägt jede geänderte Seite ihren
     * Unterschied ({@link ReportedPage#diff()}, ADR 0030, CL5). Nur auf Wunsch: je Seite eine Abfrage und bis zu
     * {@value #MAX_DIFF_STEPS} Schritte, ohne bleibt {@code diff} {@code null}.
     */
    public ChangeReport report(AccessContext caller, String sourceId, String update, boolean withDiffs) {
        return report(caller, sourceId, update, null, withDiffs);
    }

    /** Der Bericht auf der Ebene eines Space ohne Unterschiede, {@code spaceKey} {@code null} für die ganze Quelle. */
    public ChangeReport report(AccessContext caller, String sourceId, String update, String spaceKey) {
        return report(caller, sourceId, update, spaceKey, false);
    }

    /**
     * Der Bericht eines Updates auf der Ebene eines Space (ADR 0031): nur Seiten mit Zeilen, deren neuer oder alter
     * Space {@code spaceKey} ist; eine verschobene Seite steht in beiden Spaces.
     *
     * @param spaceKey ein Space, {@code null} für die ganze Quelle
     */
    public ChangeReport report(AccessContext caller, String sourceId, String update, String spaceKey,
                               boolean withDiffs) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(update, "update");
        requireReadable(caller, sourceId);
        Span span = span(sourceId, update);
        Visibility visible = new Visibility(caller);
        ChangeQuery query = rows(sourceId, spaceKey, span.from(), span.to());
        List<ChangeEntry> rows = store.changeEntries(query, entry -> visible.within(entry, query), 0,
                Integer.MAX_VALUE).entries();
        Consolidated consolidated = consolidate(span, rows);
        if (consolidated.update().initial()) {
            return new ChangeReport(consolidated.update(), List.of());
        }
        List<ReportedPage> pages = new ArrayList<>();
        for (ReportedPage page : consolidated.pages()) {
            ReportedPage located = withDirectory(page, visible);
            pages.add(withDiffs ? withDiff(caller, located, span.from(), span.to()) : located);
        }
        pages.sort(REPORT_ORDER);
        return new ChangeReport(consolidated.update(), pages);
    }

    /**
     * Der Update-Text einer Ebene (ADR 0031, Abschnitt 2), seitenweise: Kopf mit Zeitraum und Zählern der Ebene, je
     * Seite Pfad, Arten, Versionen, Zeitpunkt und geänderte Absätze. Rechte und Ebene wie {@link #report(AccessContext,
     * String, String, String, boolean)}; Unterschiede werden nur für die Seiten des Ausschnitts gelesen.
     *
     * @param update   {@link ChangeUpdate#id()} oder {@link ChangeUpdate#OPEN}
     * @param spaceKey der Space, {@code null} für die ganze Quelle
     * @param offset   erste Seite, ab 0
     * @param limit    höchstens so viele Seiten, 1 bis {@value UpdateText#MAX_LIMIT}
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws UnknownUpdateException wenn die Quelle kein solches Update hat
     */
    public UpdateText updateText(AccessContext caller, String sourceId, String update, String spaceKey, int offset,
                                 int limit) {
        return updateText(caller, sourceId, update, spaceKey, offset, limit, ZoneId.systemDefault());
    }

    /** Wie {@link #updateText(AccessContext, String, String, String, int, int)} mit Zeiten in {@code zone}. */
    public UpdateText updateText(AccessContext caller, String sourceId, String update, String spaceKey, int offset,
                                 int limit, ZoneId zone) {
        return text(caller, sourceId, update, spaceKey, offset, limit, null, Objects.requireNonNull(zone, "zone"));
    }

    /**
     * Der Update-Text als Material für ein Modell (ADR 0028, ADR 0031): nur Seiten mit KI-Freigabe, im Pfad nur
     * freigegebene und sichtbare Vorfahren, ohne Verweise und ohne Namen von Personen.
     */
    UpdateText modelText(AccessContext caller, String sourceId, String update, String spaceKey,
                         Predicate<PageKey> released, int offset, int limit) {
        return text(caller, sourceId, update, spaceKey, offset, limit, Objects.requireNonNull(released, "released"),
                null);
    }

    private UpdateText text(AccessContext caller, String sourceId, String update, String spaceKey, int offset,
                            int limit, Predicate<PageKey> released, ZoneId zone) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(update, "update");
        if (offset < 0 || limit < 1 || limit > UpdateText.MAX_LIMIT) {
            throw new IllegalArgumentException("Ausschnitt ungültig: " + offset + "/" + limit);
        }
        requireReadable(caller, sourceId);
        Span span = span(sourceId, update);
        Visibility visible = new Visibility(caller);
        ChangeQuery query = rows(sourceId, spaceKey, span.from(), span.to());
        List<ChangeEntry> rows = store.changeEntries(query, entry -> visible.within(entry, query), 0,
                Integer.MAX_VALUE).entries();
        Consolidated consolidated = consolidate(span, rows);
        List<ReportedPage> level = new ArrayList<>();
        if (!consolidated.update().initial()) {
            for (ReportedPage page : consolidated.pages()) {
                if (released == null || released.test(new PageKey(page.sourceId(), page.pageId()))) {
                    level.add(page);
                }
            }
        }
        level.sort(REPORT_ORDER);
        int total = level.size();
        List<ReportedPage> window = new ArrayList<>();
        for (ReportedPage page : level.subList(Math.min(offset, total), Math.min(offset + limit, total))) {
            ReportedPage located = released == null ? withDirectory(page, visible)
                    : page.withDirectory(releasedPath(page, visible, released), null);
            window.add(withDiff(caller, located, span.from(), span.to()));
        }
        UpdateTextRenderer.Rendered rendered = UpdateTextRenderer.render(consolidated.update(), spaceKey,
                UpdateTextRenderer.counts(level), total, offset, window, zone);
        if (released != null) {
            // Namen von Personen gehen nie in Material für ein Modell, auch nicht im Bericht (ADR 0031).
            window.replaceAll(ReportedPage::withoutEditors);
        }
        return new UpdateText(UpdateText.FORMAT, sourceId, consolidated.update().id(), spaceKey, total, offset,
                new ChangeReport(consolidated.update(), window), rendered.markdown(), rendered.sections());
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

    /**
     * Jüngste Inhaltsänderung (neu oder Inhalt geändert) je Seite seit {@code from} (einschließlich), nur in
     * {@code sourceId} und, wenn angegeben, in {@code spaceKey}; nur Zeilen, die der Aufrufer sehen darf. Nur gelesen,
     * in einem Gang durch den Index, ohne die Zeilen vorzuhalten (für die Leseempfehlung, ADR 0032 FL6). Eine
     * unbekannte oder nicht lesbare Quelle liefert nichts.
     *
     * @param spaceKey ein Space, {@code null} für die ganze Quelle
     */
    public Map<PageKey, Instant> latestContentChanges(AccessContext caller, String sourceId, String spaceKey,
                                                      Instant from) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(from, "from");
        if (!sources.get().contains(sourceId)
                || !access.permits(caller, ResourceScope.source(sourceId), AccessKind.METADATA)) {
            return Map.of();
        }
        Visibility visible = new Visibility(caller);
        ChangeQuery query = new ChangeQuery(Set.of(sourceId), Set.of(ChangeKind.NEW, ChangeKind.CONTENT_CHANGED),
                Set.of(), spaceKey, null, from, true, null, false, true);
        Map<PageKey, Instant> latest = new HashMap<>();
        // Neueste zuerst: die erste sichtbare Zeile je Seite ist die jüngste. Die Zeilen selbst bleiben nicht liegen.
        store.changeEntries(query, entry -> {
            ChangeEntry seen = visible.within(entry, query);
            if (seen != null && seen.pageId() != null && seen.at() != null) {
                latest.putIfAbsent(new PageKey(seen.sourceId(), seen.pageId()), seen.at());
            }
            return null;
        }, 0, 0);
        return latest;
    }

    // --- Zusammenfassung (ADR 0030, Abschnitt 5) ---------------------------------------------------------------

    /** Ob ein Chat-Modell für Zusammenfassungen eingestellt ist. */
    public boolean summariesAvailable() {
        return summaries != null && summaries.model().available();
    }

    /**
     * Die Zusammenfassung eines Updates, wie der Aufrufer sie sehen darf; leer, wenn keine erstellt ist, wenn sie
     * verfallen ist (eine eingegangene Seite hat die KI-Freigabe verloren) oder wenn der Aufrufer nicht alle Seiten sehen
     * darf, die in sie eingingen.
     *
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws UnknownUpdateException wenn die Quelle kein solches Update hat
     */
    public Optional<UpdateSummary> summary(AccessContext caller, String sourceId, String update) {
        return summary(caller, sourceId, update, null);
    }

    /**
     * Wie {@link #summary(AccessContext, String, String)} für eine Ebene (ADR 0031): {@code spaceKey} {@code null} für
     * die ganze Quelle, sonst die Zusammenfassung nur dieses Space.
     */
    public Optional<UpdateSummary> summary(AccessContext caller, String sourceId, String update, String spaceKey) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(update, "update");
        requireReadable(caller, sourceId);
        Span span = span(sourceId, update);
        Visibility visible = new Visibility(caller);
        Optional<StoredSummary> stored = store.summary(sourceId, update, spaceKey)
                .filter(found -> sameSpan(found, span) && valid(found, visible));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        ChangeReport report = report(caller, sourceId, update, spaceKey);
        Map<String, String> titles = new HashMap<>();
        Map<String, String> pageSpaces = new HashMap<>();
        report.pages().forEach(page -> {
            titles.put(page.pageId(), page.title());
            pageSpaces.put(page.pageId(), page.spaceKey());
        });
        stored.get().inputPages().forEach(page -> pageSpaces.putIfAbsent(page.pageId(), page.spaceKey()));
        List<SummaryStatement> statements = new ArrayList<>();
        for (StoredSummary.Statement statement : stored.get().statements()) {
            List<SummaryPage> pages = new ArrayList<>();
            for (String pageId : statement.pageIds()) {
                String title = titles.containsKey(pageId) ? titles.get(pageId)
                        : headers.apply(new PageKey(sourceId, pageId)).map(found -> found.metadata().title())
                        .orElse(null);
                pages.add(new SummaryPage(pageId, title, pageSpaces.get(pageId)));
            }
            statements.add(new SummaryStatement(statement.text(), pages, statement.title(), statement.details()));
        }
        Map<String, String> spaceNames = spaceNames(sourceId, pageSpaces);
        // Offenes Update mit neuen Änderungen oder abgeschlossenes, in das ein Schritt später nachkam.
        boolean stale = !UpdateSummarizer.reportSha256(report, page -> contentState(page, span))
                .equals(stored.get().reportSha256());
        return Optional.of(new UpdateSummary(sourceId, update, spaceKey, stored.get().text(), statements,
                stored.get().model(),
                stored.get().createdAt(), stored.get().unreadableChanges(), stale, spaceNames));
    }

    /** Anzeigenamen der Spaces, soweit bekannt, je Space über eine seiner Seiten; ohne Zusammenfassungen keine. */
    private Map<String, String> spaceNames(String sourceId, Map<String, String> pageSpaces) {
        Map<String, String> names = new HashMap<>();
        if (summaries == null) {
            return names;
        }
        Map<String, List<String>> bySpace = new TreeMap<>();
        pageSpaces.forEach((pageId, spaceKey) -> {
            if (spaceKey != null) {
                bySpace.computeIfAbsent(spaceKey, ignored -> new ArrayList<>()).add(pageId);
            }
        });
        bySpace.forEach((spaceKey, pageIds) -> {
            for (String pageId : pageIds.subList(0, Math.min(3, pageIds.size()))) {
                Optional<String> name;
                try {
                    name = summaries.material().spaceName(new PageKey(sourceId, pageId), spaceKey)
                            .filter(found -> !found.isBlank());
                } catch (RuntimeException e) {
                    // Ein fehlender Name darf nie die Zusammenfassung verbergen.
                    name = Optional.empty();
                }
                if (name.isPresent()) {
                    names.put(spaceKey, name.get());
                    break;
                }
            }
        });
        return names;
    }


    /**
     * Ob die abgelegte Zusammenfassung eines Updates für den Aufrufer noch gilt: dieselben Rechte und dieselbe
     * KI-Freigabe wie beim Lesen ({@link #summary(AccessContext, String, String, String)}), aber ohne die Spanne zu
     * prüfen und ohne Inhalt. Für die Ansicht, die eine Zusammenfassung weiter zeigt, während das Tages-Update beim
     * Verlängern seine Kennung wechselt und die neue still entsteht (Abnahme #1037); einmal je Wechsel, nie abgefragt.
     * {@code false}, wenn keine abgelegt ist.
     *
     * @throws UnknownSourceException wenn keine Quelle mit dieser ID konfiguriert ist
     */
    public boolean summaryStillValid(AccessContext caller, String sourceId, String update, String spaceKey) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(update, "update");
        requireReadable(caller, sourceId);
        Visibility visible = new Visibility(caller);
        return store.summary(sourceId, update, spaceKey).filter(found -> valid(found, visible)).isPresent();
    }

    /**
     * Startet „Zusammenfassen“ für ein Update als Auftrag; nur auf Wunsch, nie von selbst. Das Ergebnis meldet
     * {@link #subscribe} für die Quelle, sobald es abgelegt ist; eine frühere Zusammenfassung desselben Updates wird
     * ersetzt.
     *
     * @return der neue Auftrag im Zustand {@code QUEUED}; wartet oder läuft für dieses Update in dieser Ebene schon einer
     *         (ohne angeforderten Abbruch), dieser, ohne einen zweiten zu starten
     * @throws UnknownSourceException         wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws UnknownUpdateException         wenn die Quelle kein solches Update hat
     * @throws SummaryUnavailableException    ohne Modell oder für die Erstbefüllung
     */
    public JobStatus summarize(AccessContext caller, String sourceId, String update) {
        return summarize(caller, sourceId, update, null);
    }

    /**
     * Wie {@link #summarize(AccessContext, String, String)} für eine Ebene (ADR 0031): {@code spaceKey} {@code null} für
     * die ganze Quelle, sonst nur die Seiten dieses Space; der Auftrag trägt den Space als Partition.
     */
    public JobStatus summarize(AccessContext caller, String sourceId, String update, String spaceKey) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(update, "update");
        // Recht wie beim Start der Aussagenanalyse (ADR 0024): wer die Quelle lesen darf; was an das Modell geht,
        // entscheiden die Rechte je Seite und die KI-Freigabe.
        requireReadable(caller, sourceId);
        Span span = span(sourceId, update);
        if (span.initial()) {
            throw new SummaryUnavailableException(SummaryUnavailableException.Reason.INITIAL_LOAD, sourceId);
        }
        if (!summariesAvailable()) {
            throw new SummaryUnavailableException(SummaryUnavailableException.Reason.NO_MODEL, sourceId);
        }
        Summaries runtime = summaries;
        Map<String, String> parameters = spaceKey == null ? Map.of(UPDATE_PARAMETER, update)
                : Map.of(UPDATE_PARAMETER, update, SPACE_PARAMETER, spaceKey);
        synchronized (summarizing) {
            // Kein Doppelstart (die Leseansicht stößt „Zusammenfassen“ selbst an, auch aus zwei Fenstern): Wartet oder
            // läuft der Auftrag für dieses Update in dieser Ebene schon, ist er die Antwort.
            for (JobStatus open : runtime.jobs().listAll(JobType.UPDATE_SUMMARY)) {
                if (!open.state().terminal() && !open.cancelRequested() && sourceId.equals(open.sourceId())
                        && Objects.equals(spaceKey, open.partitionKey()) && parameters.equals(open.parameters())) {
                    return open;
                }
            }
            return submitSummary(caller, sourceId, update, spaceKey, span, runtime, parameters);
        }
    }

    private JobStatus submitSummary(AccessContext caller, String sourceId, String update, String spaceKey, Span span,
                                    Summaries runtime, Map<String, String> parameters) {
        return runtime.jobs().submit(JobType.UPDATE_SUMMARY, sourceId, spaceKey, null, caller.jobOwner(),
                parameters, JobTask.blocking(context -> {
                    // Mit Unterschieden (CL5); withDiff prüft das Leserecht am Inhalt je Seite.
                    ChangeReport report = report(caller, sourceId, update, spaceKey, true);
                    Visibility visible = new Visibility(caller);
                    // Lesetext nur, wo der Auftraggeber den Inhalt lesen darf; die KI-Freigabe prüft der Summarizer.
                    Function<ReportedPage, Optional<String>> text = page -> {
                        if (!access.permits(caller, ResourceScope.page(page.sourceId(), page.spaceKey(),
                                page.pageId()), AccessKind.CONTENT)) {
                            return Optional.empty();
                        }
                        try {
                            return runtime.material().text(new PageKey(page.sourceId(), page.pageId()));
                        } catch (RuntimeException e) {
                            return Optional.empty();
                        }
                    };
                    Map<String, String> reportSpaces = new LinkedHashMap<>();
                    report.pages().forEach(page -> reportSpaces.put(page.pageId(), page.spaceKey()));
                    Map<String, String> names = spaceNames(sourceId, reportSpaces);
                    UpdateSummarizer.Result result = new UpdateSummarizer(runtime.model(), runtime.released(),
                            new ChildJobs(runtime, context.jobId(), sourceId, spaceKey, caller.jobOwner()))
                            .withPlanner(runtime.planner())
                            .summarize(report, page -> releasedPath(page, visible, runtime.released()), text, names,
                                    context);
                    Instant now = runtime.clock().instant();
                    store.saveSummary(new StoredSummary(sourceId, update, spaceKey, result.text(), result.statements(),
                            runtime.model().version(), now, report.update().to() != null ? report.update().to() : now,
                            result.unreadable(), UpdateSummarizer.reportSha256(report, page -> contentState(page, span))
                                    // Fehlen Seiten, weil der Dienst sie nie annahm, gilt die Zusammenfassung als veraltet
                                    // und wird nicht als vollständig gezeigt.
                                    + (result.lost() > 0 ? "+unvollstaendig:" + result.lost() : ""),
                            result.inputPages(), span.from()));
                    context.written(1);
                }));
    }

    /**
     * Die Anfragen von „Zusammenfassen“ als Unteraufträge unter dem Auftrag (Angelo 22.11.2026): je Teil und je
     * Zusammenführen ein eigener Auftrag, die nach den Auftragsplätzen („Vorbereitung“) gleichzeitig laufen, zusammen
     * höchstens {@code ai.parallel} Anfragen zugleich. Der Oberauftrag teilt auf, wartet und führt das Ergebnis zusammen;
     * er zählt je fertigem Unterauftrag einen Schritt. Ist kein Platz für Unteraufträge frei (eine Vorbereitung) oder
     * startet keiner, übernimmt er wartende selbst, damit nichts hängt. Abbrechen des Oberauftrags bricht alle ab.
     */
    private static final class ChildJobs implements UpdateSummarizer.Runner {

        private final Summaries runtime;
        private final String parentId;
        private final String sourceId;
        private final String spaceKey;
        private final String owner;
        private final java.util.concurrent.Semaphore lanes;

        ChildJobs(Summaries runtime, String parentId, String sourceId, String spaceKey, String owner) {
            this.runtime = runtime;
            this.parentId = parentId;
            this.sourceId = sourceId;
            this.spaceKey = spaceKey;
            this.owner = owner;
            this.lanes = new java.util.concurrent.Semaphore(Math.max(1, runtime.model().parallel()));
        }

        @Override
        public <T, R> List<R> run(List<T> items, java.util.function.IntFunction<String> title,
                                  java.util.function.BiFunction<T, com.aresstack.spaceharvester.application.jobs
                                          .JobContext, R> work,
                                  com.aresstack.spaceharvester.application.jobs.JobContext context) {
            Jobs jobs = runtime.jobs();
            // Folgeaufträge belegen höchstens alle Plätze bis auf einen; der Oberauftrag hält selbst einen.
            boolean room = Math.max(1, jobs.parallelPreparations() - 1) >= 2;
            int size = items.size();
            if (!room || size <= 1) {
                // Kein Platz für Unteraufträge: im Auftrag selbst, trotzdem bis zu ai.parallel zugleich.
                return UpdateSummarizer.inJob(runtime.model()).run(items, title, work, context);
            }
            List<java.util.concurrent.CompletableFuture<R>> results = new ArrayList<>(size);
            List<java.util.concurrent.atomic.AtomicBoolean> claimed = new ArrayList<>(size);
            String[] children = new String[size];
            for (int i = 0; i < size; i++) {
                results.add(new java.util.concurrent.CompletableFuture<>());
                claimed.add(new java.util.concurrent.atomic.AtomicBoolean());
            }
            for (int i = 0; i < size; i++) {
                String step = title.apply(i);
                if (!room || size == 1 || step == null || step.isBlank()) {
                    continue;
                }
                int index = i;
                T item = items.get(i);
                Map<String, String> parameters = new LinkedHashMap<>();
                parameters.put(PARENT_PARAMETER, parentId);
                parameters.put(STEP_PARAMETER, step);
                try {
                    children[i] = jobs.submit(JobType.UPDATE_SUMMARY, sourceId, spaceKey, null, owner, parameters,
                            JobTask.blocking(child -> {
                                if (!claimed.get(index).compareAndSet(false, true)) {
                                    return;
                                }
                                child.discovered(1);
                                execute(item, work, reporting(child, context), results.get(index));
                            })).id();
                    try {
                        jobs.prioritize(children[i], owner);
                    } catch (RuntimeException ignored) {
                        // Schon gestartet.
                    }
                } catch (RuntimeException full) {
                    // Kein Platz in der Warteschlange: der Oberauftrag macht diesen Teil selbst.
                    children[i] = null;
                }
            }
            try (AutoCloseable ignored = context.whenStopRequested(() -> cancelAll(children))) {
                Instant waitingSince = runtime.clock().instant();
                java.util.Set<Integer> counted = new java.util.HashSet<>();
                while (true) {
                    boolean open = false;
                    boolean anyRunning = false;
                    Integer takeOver = null;
                    for (int i = 0; i < size; i++) {
                        if (results.get(i).isCompletedExceptionally()) {
                            // Ein Teil scheiterte endgültig (etwa Anmeldung): die übrigen sofort beenden.
                            break;
                        }
                        if (results.get(i).isDone()) {
                            if (children[i] != null && counted.add(i)) {
                                context.processed(1);
                            }
                            continue;
                        }
                        open = true;
                        if (children[i] == null) {
                            takeOver = takeOver == null ? i : takeOver;
                            continue;
                        }
                        JobStatus status = status(jobs, children[i]);
                        if (status == null || status.state().terminal()) {
                            // Abgebrochen oder gelöscht, bevor er lief: der Oberauftrag macht ihn selbst.
                            takeOver = takeOver == null ? i : takeOver;
                        } else if (status.state() == com.aresstack.spaceharvester.application.jobs.JobState.RUNNING) {
                            anyRunning = true;
                        }
                    }
                    if (!open || results.stream().anyMatch(
                            java.util.concurrent.CompletableFuture::isCompletedExceptionally)) {
                        break;
                    }
                    context.checkpoint();
                    if (takeOver == null && !anyRunning && java.time.Duration.between(waitingSince,
                            runtime.clock().instant()).compareTo(CHILD_START_GRACE) > 0) {
                        // Kein Unterauftrag bekommt einen Platz: den nächsten wartenden selbst übernehmen.
                        for (int i = 0; i < size && takeOver == null; i++) {
                            if (!results.get(i).isDone()) {
                                takeOver = i;
                            }
                        }
                    }
                    if (takeOver != null) {
                        int index = takeOver;
                        if (claimed.get(index).compareAndSet(false, true)) {
                            if (children[index] != null) {
                                try {
                                    jobs.cancelIfWaiting(children[index], owner, "vom Oberauftrag übernommen");
                                } catch (RuntimeException ignoredCancel) {
                                    // Schon beendet.
                                }
                            }
                            execute(items.get(index), work, context, results.get(index));
                        }
                        waitingSince = runtime.clock().instant();
                        continue;
                    }
                    if (anyRunning) {
                        waitingSince = runtime.clock().instant();
                    }
                    try {
                        java.util.concurrent.CompletableFuture.anyOf(results.stream().filter(f -> !f.isDone())
                                .toArray(java.util.concurrent.CompletableFuture[]::new))
                                .get(1, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
                        // weiter prüfen
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        cancelAll(children);
                        throw new com.aresstack.spaceharvester.application.jobs.JobCancelledException(
                                context.jobId());
                    }
                }
            } catch (RuntimeException e) {
                cancelAll(children);
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            for (java.util.concurrent.CompletableFuture<R> result : results) {
                if (result.isCompletedExceptionally()) {
                    cancelAll(children);
                    try {
                        result.join();
                    } catch (java.util.concurrent.CompletionException e) {
                        if (e.getCause() instanceof RuntimeException cause) {
                            throw cause;
                        }
                        throw e;
                    }
                }
            }
            List<R> answers = new ArrayList<>(size);
            for (java.util.concurrent.CompletableFuture<R> result : results) {
                try {
                    answers.add(result.join());
                } catch (java.util.concurrent.CompletionException e) {
                    cancelAll(children);
                    if (e.getCause() instanceof RuntimeException cause) {
                        throw cause;
                    }
                    throw e;
                }
            }
            return answers;
        }

        private <T, R> void execute(T item, java.util.function.BiFunction<T, com.aresstack.spaceharvester.application
                .jobs.JobContext, R> work, com.aresstack.spaceharvester.application.jobs.JobContext lane,
                                    java.util.concurrent.CompletableFuture<R> result) {
            try {
                lanes.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                com.aresstack.spaceharvester.application.jobs.JobCancelledException cancelled =
                        new com.aresstack.spaceharvester.application.jobs.JobCancelledException(lane.jobId());
                result.completeExceptionally(cancelled);
                throw cancelled;
            }
            try {
                result.complete(work.apply(item, lane));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
                throw e;
            } finally {
                lanes.release();
            }
        }

        /**
         * Der Unterauftrag, der Ausgelassenes und Unvollständiges auch dem Oberauftrag meldet: Sonst stünde
         * „Zusammenfassen“ als vollständig da, obwohl ein Teil eine Seite verlor (wie ohne Unteraufträge).
         */
        private static com.aresstack.spaceharvester.application.jobs.JobContext reporting(
                com.aresstack.spaceharvester.application.jobs.JobContext child,
                com.aresstack.spaceharvester.application.jobs.JobContext parent) {
            return (com.aresstack.spaceharvester.application.jobs.JobContext) java.lang.reflect.Proxy.newProxyInstance(
                    com.aresstack.spaceharvester.application.jobs.JobContext.class.getClassLoader(),
                    new Class<?>[] {com.aresstack.spaceharvester.application.jobs.JobContext.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("failed") || method.getName().equals("incomplete")
                                || method.getName().equals("record")) {
                            method.invoke(parent, args);
                        }
                        try {
                            return method.invoke(child, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        private static JobStatus status(Jobs jobs, String id) {
            try {
                return jobs.status(id);
            } catch (RuntimeException gone) {
                return null;
            }
        }

        private void cancelAll(String[] children) {
            for (String child : children) {
                if (child == null) {
                    continue;
                }
                try {
                    JobStatus status = runtime.jobs().status(child);
                    if (!status.state().terminal()) {
                        runtime.jobs().cancel(child, owner);
                    }
                } catch (RuntimeException ignored) {
                    // Schon beendet oder gelöscht.
                }
            }
        }
    }

    /**
     * Ob die Zusammenfassung noch gilt und der Aufrufer sie sehen darf: Jede eingegangene Seite hat noch die
     * KI-Freigabe (sonst verfällt sie, ADR 0030, Abschnitt 5) und ist für ihn sichtbar.
     */
    private boolean valid(StoredSummary stored, Visibility visible) {
        if (summaries == null) {
            return false;
        }
        for (StoredSummary.InputPage page : stored.inputPages()) {
            if (!summaries.released().test(new PageKey(stored.sourceId(), page.pageId()))
                    || !visible.page(stored.sourceId(), page.spaceKey(), page.pageId())) {
                return false;
            }
            // Gingen Absätze der Seite ein, muss der Leser ihren Inhalt lesen dürfen.
            if (page.content() && !access.permits(visible.caller,
                    ResourceScope.page(stored.sourceId(), page.spaceKey(), page.pageId()), AccessKind.CONTENT)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Stand des Inhalts einer Seite für {@code stale}: die abgelegten Schritte ab Beginn des Updates und ob der Inhalt
     * aussteht, unabhängig von den Rechten des Lesers. Geht nie nach außen.
     */
    private String contentState(ReportedPage page, Span span) {
        if (!page.kinds().contains(ChangeKind.CONTENT_CHANGED)) {
            return "";
        }
        StringBuilder state = new StringBuilder(page.contentPending() ? "pending" : "local");
        for (ContentChange step : store.contentChanges(new PageKey(page.sourceId(), page.pageId()), span.from(),
                MAX_DIFF_STEPS)) {
            state.append('|').append(step.toVersion()).append('@').append(step.at())
                    .append(step.truncated() ? "!" : "");
        }
        return state.toString();
    }

    /**
     * Verlieren Seiten die KI-Freigabe (ADR 0028), entfallen die Zusammenfassungen, in die eine von ihnen einging; die
     * Quelle wird danach gemeldet.
     */
    public void withdrawn(Collection<PageKey> pages) {
        if (summaries == null || pages.isEmpty()) {
            return;
        }
        Map<String, Set<String>> bySource = new HashMap<>();
        for (PageKey page : pages) {
            if (!summaries.released().test(page)) {
                bySource.computeIfAbsent(page.sourceId(), ignored -> new HashSet<>()).add(page.pageId());
            }
        }
        bySource.forEach((sourceId, withdrawnPages) -> {
            boolean removed = false;
            for (StoredSummary stored : store.summaries(sourceId)) {
                if (stored.inputPages().stream().anyMatch(page -> withdrawnPages.contains(page.pageId()))) {
                    store.deleteSummary(sourceId, stored.updateId(), stored.spaceKey());
                    removed = true;
                }
            }
            if (removed) {
                coalescer.mark(sourceId);
            }
        });
    }

    /** Die Zeilen einer Quelle oder eines ihrer Spaces, nach {@code after} bis {@code until} einschließlich. */
    private static ChangeQuery rows(String sourceId, String spaceKey, Instant after, Instant until) {
        return new ChangeQuery(Set.of(sourceId), Set.of(), Set.of(), spaceKey, null, after, false, until, true,
                false);
    }

    private Span span(String sourceId, String update) {
        return spans(sourceId).stream().filter(candidate -> candidate.id().equals(update)).findFirst()
                .orElseThrow(() -> new UnknownUpdateException(sourceId));
    }

    @Override
    public void close() {
        coalescer.close();
    }

    // --- Updates -------------------------------------------------------------------------------------------------

    /**
     * Zeitraum eines Updates zwischen zwei Marken.
     *
     * @param merged ob das Update mehrere abgelegte Marken eines Tages zusammenlegt ({@link #perDay})
     */
    private record Span(String id, String sourceId, Instant from, Instant to, UpdateMark mark, boolean initial,
                        boolean merged) {
    }

    private List<Span> spans(String sourceId) {
        List<UpdateMark> stored = store.marks(sourceId);
        ZoneId zone = days;
        List<UpdateMark> marks = zone == null ? stored : DailyUpdateMarks.perDay(stored, zone);
        List<Span> spans = new ArrayList<>();
        Instant previous = null;
        int raw = 0;
        for (UpdateMark mark : marks) {
            int rounds = 0;
            while (raw < stored.size() && !stored.get(raw).at().isAfter(mark.at())) {
                raw++;
                rounds++;
            }
            spans.add(new Span(id(mark.at()), sourceId, previous, mark.at(), mark,
                    mark.initial() && inventory(sourceId, mark.at()), rounds > 1));
            previous = mark.at();
        }
        spans.add(new Span(ChangeUpdate.OPEN, sourceId, previous, null, null,
                marks.isEmpty() && inventory(sourceId, null), false));
        return spans;
    }

    /**
     * Ob die abgelegte Zusammenfassung für genau diese Spanne entstand (Architekt, 21.11.2026): Mit {@link #perDay}
     * trägt das Tages-Update die Kennung der letzten Runde; eine Zusammenfassung dieser Runde allein beschreibt nicht
     * den ganzen Tag und gilt dann als nicht vorhanden. Einträge ohne Beginn (von vorher) gelten nur ohne Zusammenlegen.
     */
    private static boolean sameSpan(StoredSummary stored, Span span) {
        return stored.from() != null ? stored.from().equals(span.from()) : !span.merged();
    }

    /**
     * Ob das erste Update wirklich die Erstbefüllung war (ADR 0030, Folgen): Die meisten Seiten, die es bis zu seiner
     * Marke im Bestand gab, kamen darin als {@code NEW}. Stand die Quelle schon im Bestand, als das Protokoll begann,
     * ist das erste Update ein gewöhnliches mit den Änderungen seither. Seiten, die erst nach der Marke neu kamen,
     * zählen nicht, damit späteres Wachstum die Entscheidung nicht kippt.
     *
     * @param at die erste Marke, {@code null} solange es keine gibt
     */
    private boolean inventory(String sourceId, Instant at) {
        long pages = store.pageCount(sourceId);
        if (pages < 0) {
            return true;
        }
        String key = sourceId + '\u001f' + at + '\u001f' + pages;
        Boolean known = inventories.get(key);
        if (known != null) {
            return known;
        }
        Set<String> before = new HashSet<>();
        Set<String> after = new HashSet<>();
        ChangeQuery created = new ChangeQuery(Set.of(sourceId), Set.of(ChangeKind.NEW), Set.of(), null, null, null,
                false, null, false, false);
        for (ChangeEntry entry : store.changeEntries(created, entry -> entry, 0, Integer.MAX_VALUE).entries()) {
            (at == null || !entry.at().isAfter(at) ? before : after).add(entry.pageId());
        }
        after.removeAll(before);
        long atMark = pages - after.size();
        boolean inventory = atMark <= 0 || before.size() * 2L >= atMark;
        if (inventories.size() > 64) {
            inventories.clear();
        }
        inventories.put(key, inventory);
        return inventory;
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
                gone ? null : last.newSpaceKey(), pending && !gone, last.at(), null,
                rows.stream().map(ChangeEntry::editor).filter(Objects::nonNull).distinct().toList(),
                gone ? List.of() : last.labels());
    }

    /** Pfad und Verweis aus dem Cache; Vorfahren, die der Aufrufer nicht sehen darf, fehlen im Pfad. */
    private ReportedPage withDirectory(ReportedPage page, Visibility visible) {
        Optional<PageHeader> header = headers.apply(new PageKey(page.sourceId(), page.pageId()));
        URI url = header.map(found -> found.metadata().url()).orElse(null);
        List<String> path = path(page, header, (found, key) -> visible.page(key.sourceId(),
                found.metadata().partitionKey(), key.pageId()));
        return page.withDirectory(path, url);
    }

    /** Der Pfad für das Modell: nur Vorfahren, die der Auftraggeber sehen darf und die die KI-Freigabe haben. */
    private List<String> releasedPath(ReportedPage page, Visibility visible, Predicate<PageKey> released) {
        return path(page, headers.apply(new PageKey(page.sourceId(), page.pageId())), (found, key) -> released.test(key)
                && visible.page(key.sourceId(), found.metadata().partitionKey(), key.pageId()));
    }

    /**
     * Die Titel der übergeordneten Seiten einer Seite von oben, nur sichtbare mit KI-Freigabe wie im Pfad der
     * Zusammenfassung; leer ohne Zusammenfassungen. Für den Podcast: wo eine Meldung steht.
     */
    public List<String> ancestors(AccessContext caller, String sourceId, String pageId) {
        Objects.requireNonNull(caller, "caller");
        if (summaries == null || sourceId == null || pageId == null) {
            return List.of();
        }
        Visibility visible = new Visibility(caller);
        Predicate<PageKey> released = summaries.released();
        List<String> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String parent = headers.apply(new PageKey(sourceId, pageId)).map(found -> found.metadata().parentPageId())
                .orElse(null);
        while (parent != null && !parent.isEmpty() && path.size() < MAX_PATH_DEPTH && seen.add(parent)) {
            PageKey key = new PageKey(sourceId, parent);
            Optional<PageHeader> ancestor = headers.apply(key);
            if (ancestor.isEmpty()) {
                break;
            }
            PageHeader found = ancestor.get();
            if (released.test(key) && visible.page(sourceId, found.metadata().partitionKey(), parent)) {
                path.addFirst(found.metadata().title());
            }
            parent = found.metadata().parentPageId();
        }
        return path;
    }

    /** Titel der Vorfahren von oben, die {@code shown} zeigt. */
    private List<String> path(ReportedPage page, Optional<PageHeader> header,
                              java.util.function.BiPredicate<PageHeader, PageKey> shown) {
        List<String> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String parent = header.map(found -> found.metadata().parentPageId())
                .orElse(page.newParentId() != null ? page.newParentId() : page.oldParentId());
        while (parent != null && !parent.isEmpty() && path.size() < MAX_PATH_DEPTH && seen.add(parent)) {
            PageKey key = new PageKey(page.sourceId(), parent);
            Optional<PageHeader> ancestor = headers.apply(key);
            if (ancestor.isEmpty()) {
                break;
            }
            PageHeader found = ancestor.get();
            if (shown.test(found, key)) {
                path.addFirst(found.metadata().title());
            }
            parent = found.metadata().parentPageId();
        }
        return path;
    }

    /**
     * Die Schritte vom alten bis zum neuen Stand einer geänderten Seite (ADR 0030, Abschnitt 4): die Schritte im Update,
     * und kam der Inhalt der neuen Version erst später, auch der Schritt, der ihn brachte. Fehlt der Schritt zur neuen
     * Version, steht das als Zustand im Bericht.
     */
    private ReportedPage withDiff(AccessContext caller, ReportedPage page, Instant from, Instant to) {
        if (!page.kinds().contains(ChangeKind.CONTENT_CHANGED) || page.kind() == ChangeKind.REMOVED
                || page.kind() == ChangeKind.WITHHELD) {
            return page;
        }
        if (!access.permits(caller, ResourceScope.page(page.sourceId(), page.spaceKey(), page.pageId()),
                AccessKind.CONTENT)) {
            return page.withDiff(new ContentDiff(ContentDiff.Status.NOT_PERMITTED, List.of()));
        }
        List<ContentChange> steps = new ArrayList<>();
        boolean reached = false;
        for (ContentChange step : store.contentChanges(new PageKey(page.sourceId(), page.pageId()), from,
                MAX_DIFF_STEPS)) {
            boolean inUpdate = to == null || !step.at().isAfter(to);
            if (page.newVersion() != null && page.newVersion().equals(step.toVersion())) {
                steps.add(step);
                reached = true;
                break;
            }
            if (inUpdate) {
                steps.add(step);
            }
        }
        if (page.newVersion() == null) {
            reached = !steps.isEmpty() && !page.contentPending();
        }
        if (reached) {
            return page.withDiff(new ContentDiff(ContentDiff.Status.AVAILABLE, steps));
        }
        return page.withDiff(page.contentPending() ? new ContentDiff(ContentDiff.Status.CONTENT_PENDING, steps)
                : new ContentDiff(ContentDiff.Status.PREVIOUS_MISSING, List.of()));
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
                        entry.newParentId(), null, entry.newSpaceKey(), entry.generation(), entry.contentPending(),
                        entry.editor(), entry.labels());
            }
            return entry;
        }

        /**
         * Die Zeile wie {@link #view}, aber nur, wenn auch die gekürzte noch zur Abfrage passt: Eine Verschiebung in
         * einen verborgenen Space zählt dort nicht und verrät ihn so nicht (ADR 0031, Space-Ebene).
         */
        ChangeEntry within(ChangeEntry entry, ChangeQuery query) {
            ChangeEntry seen = view(entry);
            return seen == null || seen == entry || query.matches(seen) ? seen : null;
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

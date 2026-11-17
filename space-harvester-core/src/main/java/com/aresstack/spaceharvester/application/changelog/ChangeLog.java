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

    private final ChangeLogStore store;
    private final AccessGuard access;
    private final Function<PageKey, Optional<PageHeader>> headers;
    private final Supplier<? extends Collection<String>> sources;
    private final Listeners<ChangeLogListener> listeners = new Listeners<>("Änderungsprotokoll");
    private final Coalescer<String, String> coalescer;
    private final Summaries summaries;

    /**
     * Was „Zusammenfassen“ braucht (ADR 0030, Abschnitt 5).
     *
     * @param released ob eine Seite an den KI-Dienst darf ({@code AiRelease::mayProcess}, ADR 0028)
     */
    public record Summaries(Jobs jobs, SummaryModel model, Predicate<PageKey> released, Clock clock) {

        public Summaries {
            Objects.requireNonNull(jobs, "jobs");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(released, "released");
            Objects.requireNonNull(clock, "clock");
        }
    }

    /** Parameter des Auftrags „Zusammenfassen“: das Update. */
    public static final String UPDATE_PARAMETER = "update";

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
        List<UpdateMark> marks = store.marks(sourceId);
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
        Set<String> summarized = spaceKey == null ? store.summarizedUpdates(sourceId) : Set.of();
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
            ChangeUpdate update = consolidate(span, rows).update();
            if (summarized.contains(update.id()) && store.summary(sourceId, update.id())
                    .filter(stored -> valid(stored, visible)).isPresent()) {
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
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(update, "update");
        requireReadable(caller, sourceId);
        Span span = span(sourceId, update);
        Visibility visible = new Visibility(caller);
        Optional<StoredSummary> stored = store.summary(sourceId, update).filter(found -> valid(found, visible));
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        ChangeReport report = report(caller, sourceId, update);
        Map<String, String> titles = new HashMap<>();
        report.pages().forEach(page -> titles.put(page.pageId(), page.title()));
        List<SummaryStatement> statements = new ArrayList<>();
        for (StoredSummary.Statement statement : stored.get().statements()) {
            List<SummaryPage> pages = new ArrayList<>();
            for (String pageId : statement.pageIds()) {
                String title = titles.containsKey(pageId) ? titles.get(pageId)
                        : headers.apply(new PageKey(sourceId, pageId)).map(found -> found.metadata().title())
                        .orElse(null);
                pages.add(new SummaryPage(pageId, title));
            }
            statements.add(new SummaryStatement(statement.text(), pages));
        }
        // Offenes Update mit neuen Änderungen oder abgeschlossenes, in das ein Schritt später nachkam.
        boolean stale = !UpdateSummarizer.reportSha256(report, page -> contentState(page, span))
                .equals(stored.get().reportSha256());
        return Optional.of(new UpdateSummary(sourceId, update, stored.get().text(), statements, stored.get().model(),
                stored.get().createdAt(), stored.get().unreadableChanges(), stale));
    }

    /**
     * Startet „Zusammenfassen“ für ein Update als Auftrag; nur auf Wunsch, nie von selbst. Das Ergebnis meldet
     * {@link #subscribe} für die Quelle, sobald es abgelegt ist; eine frühere Zusammenfassung desselben Updates wird
     * ersetzt.
     *
     * @return der Auftrag im Zustand {@code QUEUED}
     * @throws UnknownSourceException         wenn keine Quelle mit dieser ID konfiguriert ist
     * @throws UnknownUpdateException         wenn die Quelle kein solches Update hat
     * @throws SummaryUnavailableException    ohne Modell oder für die Erstbefüllung
     */
    public JobStatus summarize(AccessContext caller, String sourceId, String update) {
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
        return runtime.jobs().submit(JobType.UPDATE_SUMMARY, sourceId, null, null, caller.jobOwner(),
                Map.of(UPDATE_PARAMETER, update), JobTask.blocking(context -> {
                    // Mit Unterschieden (CL5); withDiff prüft das Leserecht am Inhalt je Seite.
                    ChangeReport report = report(caller, sourceId, update, true);
                    Visibility visible = new Visibility(caller);
                    UpdateSummarizer.Result result = new UpdateSummarizer(runtime.model(), runtime.released())
                            .summarize(report, page -> releasedPath(page, visible, runtime.released()), context);
                    Instant now = runtime.clock().instant();
                    store.saveSummary(new StoredSummary(sourceId, update, result.text(), result.statements(),
                            runtime.model().version(), now, report.update().to() != null ? report.update().to() : now,
                            result.unreadable(), UpdateSummarizer.reportSha256(report, page -> contentState(page, span)),
                            result.inputPages()));
                    context.written(1);
                }));
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
            for (String update : store.summarizedUpdates(sourceId)) {
                Optional<StoredSummary> stored = store.summary(sourceId, update);
                if (stored.isPresent() && stored.get().inputPages().stream()
                        .anyMatch(page -> withdrawnPages.contains(page.pageId()))) {
                    store.deleteSummary(sourceId, update);
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

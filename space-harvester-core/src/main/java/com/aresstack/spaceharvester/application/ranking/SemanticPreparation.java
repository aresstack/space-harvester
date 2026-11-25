package com.aresstack.spaceharvester.application.ranking;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.searchrun.SearchRunId;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Vorbereitung der semantischen Suche (ADR 0013, Punkt 3): Dieselbe Schlagwortsuche wie ohne Haken findet die Seiten
 * und lädt sie mit den Rechten des Aufrufers in den Cache ({@link KeywordSearch}). Für ihre Treffer, also nur Seiten,
 * die der Aufrufer lesen darf, berechnet die Vorbereitung fehlende oder veraltete Embeddings im Aufruf, begrenzt
 * parallel und höchstens {@link #DEFAULT_WAIT} lang. Was nach der Frist noch nicht angefangen hat, geht an die
 * Warteschlange der KI-Anreicherung ({@link Enrichments#recomputeInBackground}), statt wie früher (Fundstelle 11,
 * Abnahme #1078) je Suche einen eigenen Auftrag „Suchtreffer vorbereiten“ anzulegen, der einen Platz belegte und
 * andere Aufträge warten ließ (Angelo 24.11.2026). Nur die höchstens vier schon laufenden Berechnungen enden im
 * Aufruf. Seiten aus Quellen ohne Freigabe für externe Verarbeitung lässt der Embedding-Prozessor selbst aus.
 */
public final class SemanticPreparation implements RelevanceSearch.Preparation {

    private static final System.Logger LOG = System.getLogger(SemanticPreparation.class.getName());

    /** So viele Seiten findet die Vorbereitung je Suche höchstens. */
    public static final int MAX_PAGES = 50;
    /** So lange wartet sie höchstens auf Laden und Embeddings zusammen. */
    public static final Duration DEFAULT_WAIT = Duration.ofSeconds(10);

    private static final int PARALLEL = 4;
    /** Ergebnis einer Seite, die nach der Frist an die Hintergrundaufträge ging. */
    private static final Enrichments.Run HANDED_OVER = new Enrichments.Run(true, 0, 0, 0);
    private static final long POLL_MILLIS = 25;

    private final KeywordSearch keywords;
    private final Function<PageKey, Optional<PageSnapshot>> snapshots;
    private final Enrichments enrichments;
    private final String processorId;
    private final Duration wait;
    /**
     * Ob der Rest nach der Frist an die KI-Anreicherung geht (mit {@link Jobs}); ohne sie rechnet er wie früher im
     * Aufruf weiter.
     */
    private final Jobs jobs;
    private final java.util.function.BiPredicate<PageSnapshot, String> localPreparation;

    /** Angabe an Aufträgen „Suchtreffer vorbereiten“ früherer Fassungen ({@code task}); neue entstehen nicht mehr. */
    public static final String TASK = "prepare-search-hits";
    /** Phase dieser früheren Aufträge in der Auftragsliste. */
    public static final String PHASE = "Suchtreffer vorbereiten";

    /**
     * @param snapshots   die abgelegte Seite im Cache
     * @param processorId Kennung des Embedding-Prozessors in {@code enrichments}
     */
    public SemanticPreparation(KeywordSearch keywords, Function<PageKey, Optional<PageSnapshot>> snapshots,
                               Enrichments enrichments, String processorId, Duration wait) {
        this(keywords, snapshots, enrichments, processorId, wait, null);
    }

    /** @param jobs für den Rest nach der Frist ({@value #PHASE}) oder {@code null} */
    public SemanticPreparation(KeywordSearch keywords, Function<PageKey, Optional<PageSnapshot>> snapshots,
                               Enrichments enrichments, String processorId, Duration wait, Jobs jobs) {
        this(keywords, snapshots, enrichments, processorId, wait, jobs, (snapshot, id) -> false);
    }

    public SemanticPreparation(KeywordSearch keywords, Function<PageKey, Optional<PageSnapshot>> snapshots,
                               Enrichments enrichments, String processorId, Duration wait, Jobs jobs,
                               java.util.function.BiPredicate<PageSnapshot, String> localPreparation) {
        this.localPreparation = Objects.requireNonNull(localPreparation, "localPreparation");
        this.jobs = jobs;
        this.keywords = Objects.requireNonNull(keywords, "keywords");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.processorId = Objects.requireNonNull(processorId, "processorId");
        this.wait = Objects.requireNonNull(wait, "wait");
    }

    @Override
    public int prepare(AccessContext caller, PageSearchQuery query, String text) {
        return prepare(caller, query, text, Set.of(), null);
    }

    /** Seiten aus {@code excludedSpaceKeys} findet und lädt die Schlagwortsuche gar nicht erst. */
    @Override
    public int prepare(AccessContext caller, PageSearchQuery query, String text, Set<String> excludedSpaceKeys) {
        return prepare(caller, query, text, excludedSpaceKeys, null);
    }

    /**
     * Die Schlagwortsuche der Vorbereitung ist ein Teillauf von {@code partOf} im Suchlauf-Protokoll (#182); Seiten aus
     * {@code excludedSpaceKeys} findet und lädt sie gar nicht erst.
     */
    @Override
    public int prepare(AccessContext caller, PageSearchQuery query, String text, Set<String> excludedSpaceKeys,
                       SearchRunId partOf) {
        return prepare(caller, query, text, excludedSpaceKeys, partOf, false);
    }

    /** Mit {@code glossarySynonyms} findet die Schlagwortsuche auch über Synonyme bestätigter Fachbegriffe (K5a). */
    @Override
    public int prepare(AccessContext caller, PageSearchQuery query, String text, Set<String> excludedSpaceKeys,
                       SearchRunId partOf, boolean glossarySynonyms) {
        long deadline = System.nanoTime() + wait.toNanos();
        KeywordSearch.Query keywordQuery = new KeywordSearch.Query(shortened(text), query.sourceId(),
                query.sourceIds(), query.spaceKeys(), excludedSpaceKeys, query.pages());
        if (glossarySynonyms) {
            keywordQuery = keywordQuery.withGlossarySynonyms(true);
        }
        KeywordSearch.Results found = keywords.search(caller, keywordQuery, null, MAX_PAGES, partOf);
        if (awaitLoading(found.progress(), deadline)) {
            // Die Schlagwortsuche wartet nicht aufs Laden; erst danach findet der Volltextindex die Seiten.
            found = keywords.search(caller, keywordQuery, null, MAX_PAGES, partOf);
        }
        int pending = found.loading() + (lostCandidates(found) ? 1 : 0);
        // Jede Seite einmal, auch wenn die Schlagwortsuche sie mehrfach liefert.
        java.util.Map<PageKey, PageSnapshot> unique = new java.util.LinkedHashMap<>();
        Set<PageKey> visited = new java.util.HashSet<>();
        for (KeywordSearch.Hit hit : found.hits()) {
            if (!keywords.allowsPrefetch(hit.sourceId())) {
                continue;
            }
            PageKey key = new PageKey(hit.sourceId(), hit.pageId());
            if (!visited.add(key)) {
                continue;
            }
            Optional<PageSnapshot> page = snapshots.apply(key);
            if (page.isPresent() && com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference
                    .recognizes(page.get().payload())) {
                if (!localPreparation.test(page.get(), processorId)) {
                    pending++;
                }
            } else {
                page.filter(this::withoutCurrentEmbedding).ifPresent(snapshot -> unique.put(key, snapshot));
            }
        }
        if (unique.isEmpty()) {
            return pending;
        }
        Semaphore slots = new Semaphore(PARALLEL);
        // Wer eine Seite rechnet: eine Berechnung des Aufrufs oder, nach der Frist, die KI-Anreicherung.
        Set<PageKey> claimed = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<Future<Enrichments.Run>> runs = new ArrayList<>(unique.size());
        ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (java.util.Map.Entry<PageKey, PageSnapshot> entry : unique.entrySet()) {
                PageKey key = entry.getKey();
                PageSnapshot snapshot = entry.getValue();
                runs.add(runner.submit(() -> {
                    slots.acquireUninterruptibly();
                    try {
                        // Nach der Frist wartet niemand mehr (Fundstelle 11): Was noch nicht angefangen hat, rechnet
                        // die KI-Anreicherung statt eines Threads ohne Auftrag.
                        if (jobs != null && System.nanoTime() >= deadline || !claimed.add(key)) {
                            return HANDED_OVER;
                        }
                        return enrichments.recompute(snapshot, processorId);
                    } finally {
                        slots.release();
                    }
                }));
            }
            for (Future<Enrichments.Run> run : runs) {
                try {
                    Enrichments.Run result = run.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (result == HANDED_OVER) {
                        // Rechnet sichtbar weiter und zählt beim nächsten Mal; diese Suche ist unvollständig.
                        pending++;
                    } else if (result.failed() > 0) {
                        // Der Dienst hat abgelehnt oder war nicht erreichbar; die nächste Suche versucht es wieder.
                        pending++;
                    }
                } catch (TimeoutException e) {
                    // Rechnet weiter und zählt beim nächsten Mal; diese Suche ist unvollständig.
                    pending++;
                } catch (ExecutionException e) {
                    LOG.log(System.Logger.Level.INFO, "Embedding einer gefundenen Seite gescheitert ({0})",
                            e.getCause() == null ? "?" : e.getCause().getClass().getName());
                    pending++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pending++;
                }
            }
        } finally {
            // Nicht unterbrechen: Ein Abbruch mitten im Schreiben schlösse die Dateikanäle des Lucene-Index.
            runner.shutdown();
        }
        if (jobs != null) {
            java.util.Map<PageKey, PageSnapshot> left = new java.util.LinkedHashMap<>();
            unique.forEach((key, page) -> {
                if (claimed.add(key)) {
                    left.put(key, page);
                }
            });
            if (!left.isEmpty()) {
                handOver(left);
            }
        }
        flush();
        return pending;
    }

    private void flush() {
        try {
            enrichments.flush();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "Embeddings nicht dauerhaft gemacht ({0})", e.getClass().getName());
        }
    }

    /**
     * Gibt den Rest nach der Frist an die Warteschlange der KI-Anreicherung (Angelo 24.11.2026: „scheiß Aufträge
     * Suchtreffer vorbereiten … die blockieren alles“): kein eigener Auftrag je Suche und Suchvariante mehr, der einen
     * Platz belegt, sondern je Quelle der eine Teil „KI-Anreicherung“ im Hintergrund. Er nimmt jede Seite höchstens
     * einmal, rechnet nach {@code ai.parallel}, lässt der offenen Ansicht den Vortritt, wartet ohne Netz und wiederholt
     * eine gescheiterte Berechnung mit Grund in der Diagnose. Ein gescheitertes oder veraltetes Embedding wird neu
     * gerechnet, darum erzwungen.
     */
    private void handOver(java.util.Map<PageKey, PageSnapshot> left) {
        int handed = 0;
        for (PageSnapshot snapshot : left.values()) {
            try {
                handed += enrichments.recomputeInBackground(snapshot, List.of(processorId)) > 0 ? 1 : 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.INFO, "Suchtreffer nicht an die KI-Anreicherung übergeben ({0}); die "
                        + "nächste Suche versucht es wieder", e.getClass().getName());
            }
        }
        LOG.log(System.Logger.Level.DEBUG, "{0} von {1} Suchtreffern an die KI-Anreicherung übergeben", handed,
                left.size());
    }

    /**
     * Räumt Aufträge „Suchtreffer vorbereiten“ früherer Fassungen weg, die das Beenden des Dienstes unterbrach
     * (Angelo 24.11.2026): Ihre Arbeit lag nur im Speicher, sie ließen sich nie fortsetzen und stünden sonst rot in
     * der Liste. Fehlende Embeddings holt die nächste Suche über die KI-Anreicherung nach.
     *
     * @return wie viele entfernt wurden
     */
    public static int forgetInterrupted(Jobs jobs) {
        int forgotten = 0;
        for (com.aresstack.spaceharvester.application.jobs.JobStatus status
                : jobs.listAll(com.aresstack.spaceharvester.application.jobs.JobType.ENRICHMENT)) {
            if (!TASK.equals(status.parameter("task")) || status.state()
                    != com.aresstack.spaceharvester.application.jobs.JobState.FAILED || status.error() == null
                    || !com.aresstack.spaceharvester.application.jobs.JobLifecycleState.INTERRUPTED.equals(status.error().code())) {
                continue;
            }
            try {
                jobs.delete(status.id(), status.owner());
                forgotten++;
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.INFO, "Unterbrochener Auftrag „{0}“ nicht entfernt ({1})", PHASE,
                        e.getClass().getName());
            }
        }
        return forgotten;
    }

    /** Wartet bis zur Frist, bis die Funde der Suche geladen sind; {@code true}, wenn etwas zu laden war. */
    private boolean awaitLoading(KeywordSearch.Progress progress, long deadline) {
        if (progress == null || progress.done() || progress.cancelled()) {
            return false;
        }
        while (System.nanoTime() < deadline) {
            Optional<KeywordSearch.Progress> now = keywords.progress(progress.id());
            if (now.isEmpty() || now.get().done() || now.get().cancelled()) {
                break;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return true;
    }

    /** Nur ein aktuelles, gelungenes Embedding gilt als vorbereitet; ein gescheitertes wird neu versucht. */
    private boolean withoutCurrentEmbedding(PageSnapshot snapshot) {
        return enrichments.view(snapshot).stream()
                .noneMatch(view -> processorId.equals(view.enrichment().processorId())
                        && view.freshness() == Enrichment.Freshness.CURRENT
                        && view.enrichment().status() == Enrichment.Status.OK);
    }

    /**
     * Ob die Schlagwortsuche Kandidaten verlor: eine Quelle nicht erreichbar, oder unvollständig ohne volle Seite
     * mit Fortsetzung. Dass hinter den ersten {@link #MAX_PAGES} weitere Treffer folgen, ist gewollt und keine Lücke.
     */
    private static boolean lostCandidates(KeywordSearch.Results found) {
        boolean unavailable = found.sources().stream()
                .anyMatch(state -> state.status() == KeywordSearch.SourceState.Status.UNAVAILABLE);
        return unavailable || !found.complete() && (found.nextCursor() == null || found.hits().size() < MAX_PAGES);
    }

    /** Die Schlagwortsuche nimmt höchstens {@value KeywordSearch#MAX_TEXT_LENGTH} Zeichen, an einer Wortgrenze. */
    static String shortened(String text) {
        String stripped = text.strip();
        if (stripped.length() <= KeywordSearch.MAX_TEXT_LENGTH) {
            return stripped;
        }
        int cut = stripped.lastIndexOf(' ', KeywordSearch.MAX_TEXT_LENGTH);
        return stripped.substring(0, cut > 0 ? cut : KeywordSearch.MAX_TEXT_LENGTH).strip();
    }
}

package com.aresstack.spaceharvester.application.ranking;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * parallel und höchstens {@link #DEFAULT_WAIT} lang. Seiten aus Quellen ohne Freigabe für externe Verarbeitung lässt
 * der Embedding-Prozessor selbst aus.
 */
public final class SemanticPreparation implements RelevanceSearch.Preparation {

    private static final System.Logger LOG = System.getLogger(SemanticPreparation.class.getName());

    /** So viele Seiten findet die Vorbereitung je Suche höchstens. */
    public static final int MAX_PAGES = 50;
    /** So lange wartet sie höchstens auf Laden und Embeddings zusammen. */
    public static final Duration DEFAULT_WAIT = Duration.ofSeconds(10);

    private static final int PARALLEL = 4;
    private static final long POLL_MILLIS = 25;

    private final KeywordSearch keywords;
    private final Function<PageKey, Optional<PageSnapshot>> snapshots;
    private final Enrichments enrichments;
    private final String processorId;
    private final Duration wait;

    /**
     * @param snapshots   die abgelegte Seite im Cache
     * @param processorId Kennung des Embedding-Prozessors in {@code enrichments}
     */
    public SemanticPreparation(KeywordSearch keywords, Function<PageKey, Optional<PageSnapshot>> snapshots,
                               Enrichments enrichments, String processorId, Duration wait) {
        this.keywords = Objects.requireNonNull(keywords, "keywords");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.processorId = Objects.requireNonNull(processorId, "processorId");
        this.wait = Objects.requireNonNull(wait, "wait");
    }

    @Override
    public int prepare(AccessContext caller, PageSearchQuery query, String text) {
        long deadline = System.nanoTime() + wait.toNanos();
        KeywordSearch.Query keywordQuery = new KeywordSearch.Query(shortened(text), query.sourceId(),
                query.sourceIds(), query.spaceKeys());
        KeywordSearch.Results found = keywords.search(caller, keywordQuery, null, MAX_PAGES);
        if (awaitLoading(found.progress(), deadline)) {
            // Die Schlagwortsuche wartet nicht aufs Laden; erst danach findet der Volltextindex die Seiten.
            found = keywords.search(caller, keywordQuery, null, MAX_PAGES);
        }
        int pending = found.loading() + (lostCandidates(found) ? 1 : 0);
        List<PageSnapshot> missing = new ArrayList<>();
        for (KeywordSearch.Hit hit : found.hits()) {
            snapshots.apply(new PageKey(hit.sourceId(), hit.pageId())).filter(this::withoutCurrentEmbedding)
                    .ifPresent(missing::add);
        }
        if (missing.isEmpty()) {
            return pending;
        }
        Semaphore slots = new Semaphore(PARALLEL);
        List<Future<Enrichments.Run>> runs = new ArrayList<>(missing.size());
        ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (PageSnapshot snapshot : missing) {
                runs.add(runner.submit(() -> {
                    slots.acquireUninterruptibly();
                    try {
                        return enrichments.recompute(snapshot, processorId);
                    } finally {
                        slots.release();
                    }
                }));
            }
            for (Future<Enrichments.Run> run : runs) {
                try {
                    if (run.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS).failed() > 0) {
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
        try {
            enrichments.flush();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "Embeddings nicht dauerhaft gemacht ({0})", e.getClass().getName());
        }
        return pending;
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

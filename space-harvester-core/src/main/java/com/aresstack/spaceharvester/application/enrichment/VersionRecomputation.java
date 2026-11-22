package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.background.BackgroundKind;
import com.aresstack.spaceharvester.application.background.BackgroundPart;
import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobCapacityExceededException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;

/**
 * Rechnet beim Start einmal die Ergebnisse der lokalen Prozessoren neu, die eine ältere Prozessorversion tragen, etwa
 * nach einem Update mit geänderter Akronymerkennung: Sonst zählten sie nicht mehr (der Begriffsindex liest nur die
 * aktuelle Version von {@code terms}) und kämen erst zurück, wenn jede Seite einzeln neu angereichert wird.
 *
 * <p>Lokal heißt: Der Prozessor rechnet ohne Netz ({@link EnrichmentProcessor#background()} {@code false}). Prozessoren,
 * die über den KI-Dienst rechnen, bleiben bei {@link ReleaseRecomputation} und der Suche. Gerechnet wird allein aus dem
 * Cache, Seite für Seite, also gedrosselt und ohne den Start aufzuhalten; nur Prozessoren mit gespeichertem Ergebnis
 * einer anderen Version, die die Seite bearbeiten. Ein fehlendes Ergebnis entsteht wie bisher beim nächsten Abgleich
 * oder Öffnen der Seite. Das Log nennt nur Quelle und Anzahl, nie Seiten.
 *
 * <p>Mit Hintergrundaufträgen ({@link #background}) läuft der Abgleich sichtbar als Art {@value #BACKGROUND_KIND}
 * „{@value #BACKGROUND_TITLE}“ mit einem Teil je Quelle (ADR 0025 Nachtrag, Punkt 9), sonst (Dienst) in einem eigenen
 * Thread.
 */
public final class VersionRecomputation implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(VersionRecomputation.class.getName());
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(10);
    /** Art des Abgleichs als Hintergrundauftrag. */
    public static final String BACKGROUND_KIND = "versions";
    /** Titel der Gruppe in der Auftragsliste. */
    public static final String BACKGROUND_TITLE = "Analysen nach Update nachrechnen";

    private final Enrichments enrichments;
    private final ReleaseRecomputation.CachedPages pages;
    private final ExecutorService runs = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("enrichment-versions-", 0).factory());
    private volatile boolean closed;
    /** Laufende Hintergrundteile; {@link #close} wartet auf sie. */
    private final ActiveParts parts = new ActiveParts();
    /** Hintergrundaufträge; ohne sie im eigenen Thread (Dienst). */
    private volatile BackgroundJobs background;
    /** Anzahl der bekannten Seiten einer Quelle, für den Fortschritt eines Teils. */
    private volatile ToLongFunction<String> pageCount = sourceId -> 0;

    /** @param pages die Seiten einer Quelle im Cache */
    public VersionRecomputation(Enrichments enrichments, ReleaseRecomputation.CachedPages pages) {
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.pages = Objects.requireNonNull(pages, "pages");
    }

    /**
     * Läuft als Hintergrundauftrag (Art {@value #BACKGROUND_KIND}, ein Teil je Quelle), sichtbar im Reiter
     * „Hintergrund“, anhaltbar und abbrechbar. Der Teil bestimmt seine Arbeit allein aus dem Cache; nach einem Neustart
     * setzt ein unterbrochener Teil von vorn fort, Aktuelles kostet dann nur den Blick in die Ablage.
     *
     * @param pageCount Anzahl der bekannten Seiten einer Quelle, für den Fortschritt
     */
    public void background(BackgroundJobs background, ToLongFunction<String> pageCount) {
        Objects.requireNonNull(background, "background");
        this.pageCount = Objects.requireNonNull(pageCount, "pageCount");
        // Höchstens ein offener Teil je Quelle: Er bestimmt seine Arbeit allein aus dem Cache.
        background.register(BackgroundKind.exclusive(BACKGROUND_KIND,
                part -> JobTask.blocking(context -> parts.run(() -> closed,
                        () -> {
                            throw new JobCancelledException(context.jobId());
                        },
                        () -> run(part.sourceId(), context))), () -> true));
        this.background = background;
    }

    /**
     * Reiht den Abgleich für die Quellen ein und kehrt sofort zurück; mit Hintergrundaufträgen eine Gruppe mit einem
     * Teil je Quelle, für die nicht schon ein Teil wartet oder läuft, etwa nach einem Neustart fortgesetzt.
     */
    public void reconcileOnStart(Collection<String> sourceIds) {
        List<String> ids = List.copyOf(sourceIds);
        BackgroundJobs hosted = background;
        if (hosted != null) {
            try {
                hosted.startUnlessRunning(BACKGROUND_KIND, BACKGROUND_TITLE,
                        ids.stream().map(sourceId -> new BackgroundPart.Draft(sourceId, null)).toList());
            } catch (JobCapacityExceededException | IllegalStateException e) {
                LOG.log(System.Logger.Level.INFO, "Abgleich der Prozessorversionen beim Start nicht gestartet ({0})",
                        e.getClass().getSimpleName());
            }
            return;
        }
        try {
            runs.execute(() -> ids.forEach(sourceId -> run(sourceId, null)));
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.INFO, "Abgleich der Prozessorversionen beim Start nicht gestartet");
        }
    }

    /**
     * Ein Abgleich für eine Quelle; mit {@code context} als Teil eines Hintergrundauftrags, dann enden Fehler,
     * Anhalten und Abbruch den Teil, und das Beenden des Dienstes unterbricht ihn.
     */
    private void run(String sourceId, JobContext context) {
        if (closed && context == null) {
            return;
        }
        int seen = 0;
        int count = 0;
        try {
            Iterator<PageSnapshot> snapshots = pages.of(sourceId);
            long total = context == null ? 0 : pageCount.applyAsLong(sourceId);
            if (total > 0) {
                context.discovered(total);
            }
            while (!closed && snapshots.hasNext()) {
                if (context != null) {
                    context.checkpoint();
                }
                PageSnapshot snapshot = snapshots.next();
                seen++;
                List<String> due = outdated(snapshot);
                boolean recomputed = !due.isEmpty() && enrichments.recomputeInBackground(snapshot, due) > 0;
                if (recomputed) {
                    count++;
                }
                if (context != null) {
                    context.processed(1);
                    if (recomputed) {
                        context.written(1);
                    }
                }
            }
            enrichments.flush();
            if (context != null) {
                if (closed) {
                    // Nicht als fertig melden: Das Beenden des Dienstes unterbricht den Teil ({@code Jobs}), er setzt
                    // nach dem Neustart fort.
                    throw new JobCancelledException(context.jobId());
                }
                // Seiten nur mit Kopfdaten liefert der Cache nicht; sie haben nichts zu rechnen.
                if (total > seen) {
                    context.processed(total - seen);
                }
            }
            if (count > 0) {
                LOG.log(System.Logger.Level.INFO, "Abgleich der Prozessorversionen beim Start, Quelle {0}: {1} von {2} "
                        + "Seiten neu berechnet", sourceId, count, seen);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (context != null) {
                throw new JobCancelledException(context.jobId());
            }
        } catch (JobCancelledException | JobFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Abgleich der Prozessorversionen beim Start, Quelle {0}, nach {1} "
                    + "Seiten abgebrochen ({2})", sourceId, count, e.getClass().getName());
            if (context != null) {
                throw e;
            }
        }
    }

    /** Die lokalen Prozessoren, deren gespeichertes Ergebnis eine andere Version trägt; liest nur die Ablage. */
    private List<String> outdated(PageSnapshot snapshot) {
        List<String> due = new ArrayList<>();
        for (Enrichment stored : enrichments.store().find(snapshot.key())) {
            String processorId = stored.processorId();
            if (enrichments.local(processorId) && enrichments.appliesTo(processorId, snapshot.key())
                    && !enrichments.version(processorId).map(stored.processorVersion()::equals).orElse(true)) {
                due.add(processorId);
            }
        }
        return due;
    }

    @Override
    public void close() throws InterruptedException {
        closed = true;
        runs.shutdown();
        if (!runs.awaitTermination(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            runs.shutdownNow();
        }
        if (!parts.await(CLOSE_WAIT)) {
            LOG.log(System.Logger.Level.WARNING, "Abgleich der Prozessorversionen beim Beenden nicht rechtzeitig fertig");
        }
    }
}

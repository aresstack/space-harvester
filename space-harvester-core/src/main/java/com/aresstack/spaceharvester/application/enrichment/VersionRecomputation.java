package com.aresstack.spaceharvester.application.enrichment;

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

/**
 * Rechnet beim Start einmal die Ergebnisse der lokalen Prozessoren neu, die eine ältere Prozessorversion tragen, etwa
 * nach einem Update mit geänderter Akronymerkennung: Sonst zählten sie nicht mehr (der Begriffsindex liest nur die
 * aktuelle Version von {@code terms}) und kämen erst zurück, wenn jede Seite einzeln neu angereichert wird.
 *
 * <p>Lokal heißt: Der Prozessor rechnet ohne Netz ({@link EnrichmentProcessor#background()} {@code false}). Prozessoren,
 * die über den KI-Dienst rechnen, bleiben bei {@link ReleaseRecomputation} und der Suche. Gerechnet wird allein aus dem
 * Cache, Seite für Seite in einem eigenen Thread, also gedrosselt und ohne den Start aufzuhalten; nur Prozessoren mit
 * gespeichertem Ergebnis einer anderen Version, die die Seite bearbeiten. Ein fehlendes Ergebnis entsteht wie bisher
 * beim nächsten Abgleich oder Öffnen der Seite. Das Log nennt nur Quelle und Anzahl, nie Seiten.
 */
public final class VersionRecomputation implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(VersionRecomputation.class.getName());
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(10);

    private final Enrichments enrichments;
    private final ReleaseRecomputation.CachedPages pages;
    private final ExecutorService runs = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("enrichment-versions-", 0).factory());
    private volatile boolean closed;

    /** @param pages die Seiten einer Quelle im Cache */
    public VersionRecomputation(Enrichments enrichments, ReleaseRecomputation.CachedPages pages) {
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.pages = Objects.requireNonNull(pages, "pages");
    }

    /** Reiht den Abgleich für die Quellen ein; kehrt sofort zurück. */
    public void reconcileOnStart(Collection<String> sourceIds) {
        List<String> ids = List.copyOf(sourceIds);
        try {
            runs.execute(() -> ids.forEach(this::run));
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.INFO, "Abgleich der Prozessorversionen beim Start nicht gestartet");
        }
    }

    private void run(String sourceId) {
        if (closed) {
            return;
        }
        int seen = 0;
        int count = 0;
        try {
            Iterator<PageSnapshot> snapshots = pages.of(sourceId);
            while (!closed && snapshots.hasNext()) {
                PageSnapshot snapshot = snapshots.next();
                seen++;
                List<String> due = outdated(snapshot);
                if (!due.isEmpty() && enrichments.recomputeInBackground(snapshot, due) > 0) {
                    count++;
                }
            }
            enrichments.flush();
            if (count > 0) {
                LOG.log(System.Logger.Level.INFO, "Abgleich der Prozessorversionen beim Start, Quelle {0}: {1} von {2} "
                        + "Seiten neu berechnet", sourceId, count, seen);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Abgleich der Prozessorversionen beim Start, Quelle {0}, nach {1} "
                    + "Seiten abgebrochen ({2})", sourceId, count, e.getClass().getName());
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
    }
}

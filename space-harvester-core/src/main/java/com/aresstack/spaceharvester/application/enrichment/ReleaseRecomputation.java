package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.configuration.ConfigurationChange;
import com.aresstack.spaceharvester.application.configuration.ConfigurationListener;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Rechnet nach einem Wechsel der Freigabe {@code ai.external-processing-sources} (ADR 0015) die Seiten einer Quelle im
 * Cache für die Prozessoren neu, deren Ergebnis an der Freigabe hängt (im Kern {@code embedding}, {@code passages},
 * {@code navigation}; {@code images} rechnet nur auf Abruf und prüft die Freigabe dann selbst). Die Version bleibt
 * dieselbe, deshalb erzwungen über {@link Enrichments#recomputeInBackground}, gedrosselt im vorhandenen Hintergrund und
 * ohne neuen Abruf von der Quelle:
 *
 * <ul>
 *     <li>neu freigegeben: mit Modell;</li>
 *     <li>entzogen: ohne Modell auf den Rückfall (Absätze und Navigation nach Struktur, keine Embeddings). Ob eine
 *     Berechnung das Modell fragen darf, entscheidet ihre Ausführung, nicht das Einreihen; ab dem Aktivieren fragt
 *     also auch keine schon eingereihte Berechnung mehr das Modell.</li>
 * </ul>
 *
 * Läufe gehen nacheinander; wechselt die Freigabe während eines Laufs erneut, endet er, und der nächste übernimmt. Das
 * Log nennt nur Quelle und Anzahl, nie Seiten.
 *
 * <p>Als {@link ConfigurationListener} muss er nach dem Beobachter angemeldet sein, der das Embedding-Modell tauscht:
 * {@code released} fragt die Freigabe so, wie das Modell sie sieht.
 */
public final class ReleaseRecomputation implements ConfigurationListener, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(ReleaseRecomputation.class.getName());
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(10);

    private final Enrichments enrichments;
    private final CachedPages pages;
    private final Predicate<String> released;
    private final List<String> processorIds;
    private final ExecutorService runs = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("enrichment-release-", 0).factory());
    private volatile boolean closed;

    /**
     * @param pages        die Seiten einer Quelle im Cache
     * @param released     ob Inhalte der Quelle jetzt an den KI-Dienst gehen, z. B.
     *                     {@code EmbeddingProcessor::covers}
     * @param processorIds die Prozessoren, deren Ergebnis an der Freigabe hängt
     */
    public ReleaseRecomputation(Enrichments enrichments, CachedPages pages, Predicate<String> released,
                                List<String> processorIds) {
        this.enrichments = Objects.requireNonNull(enrichments, "enrichments");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.released = Objects.requireNonNull(released, "released");
        this.processorIds = List.copyOf(processorIds);
    }

    /** Die Seiten einer Quelle mit gespeichertem Snapshot, nach und nach gelesen. */
    @FunctionalInterface
    public interface CachedPages {

        Iterator<PageSnapshot> of(String sourceId);
    }

    @Override
    public Preparation prepare(ConfigurationChange change) {
        Map<String, Boolean> changed = changes(change.previous().ai(), change.next().ai());
        if (changed.isEmpty()) {
            return Preparation.NOTHING;
        }
        return () -> changed.forEach(this::recompute);
    }

    /**
     * Quellen, deren Freigabe wechselt: {@code true} neu freigegeben, {@code false} entzogen; leer, wenn weder vorher
     * noch nachher ein Embedding-Modell eingestellt ist.
     */
    static Map<String, Boolean> changes(AiServiceSettings before, AiServiceSettings after) {
        if (!before.embeddings() && !after.embeddings()
                || before.externalProcessingSources().equals(after.externalProcessingSources())) {
            return Map.of();
        }
        Map<String, Boolean> changed = new TreeMap<>();
        after.externalProcessingSources().stream().filter(sourceId -> !before.mayProcessExternally(sourceId))
                .forEach(sourceId -> changed.put(sourceId, true));
        before.externalProcessingSources().stream().filter(sourceId -> !after.mayProcessExternally(sourceId))
                .forEach(sourceId -> changed.put(sourceId, false));
        return changed;
    }

    /** Reiht einen Lauf für die Quelle ein; schließt der Dienst schon, gilt die Freigabe beim nächsten Abruf. */
    void recompute(String sourceId, boolean release) {
        try {
            runs.execute(() -> run(sourceId, release));
        } catch (RejectedExecutionException e) {
            LOG.log(System.Logger.Level.INFO, "Neuberechnung nach Wechsel der Freigabe, Quelle {0}, nicht gestartet",
                    sourceId);
        }
    }

    private void run(String sourceId, boolean release) {
        int count = 0;
        try {
            Iterator<PageSnapshot> snapshots = pages.of(sourceId);
            // Wechselt die Freigabe währenddessen wieder, übernimmt der nächste Lauf.
            while (!closed && released.test(sourceId) == release && snapshots.hasNext()) {
                if (enrichments.recomputeInBackground(snapshots.next(), processorIds) > 0) {
                    count++;
                }
            }
            enrichments.flush();
            LOG.log(System.Logger.Level.INFO, release
                    ? "Freigabe für den KI-Dienst erteilt, Quelle {0}: {1} Seiten zur Neuberechnung mit Modell"
                    : "Freigabe für den KI-Dienst entzogen, Quelle {0}: {1} Seiten zur Neuberechnung ohne Modell",
                    sourceId, count);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Neuberechnung nach Wechsel der Freigabe, Quelle {0}, nach {1} "
                    + "Seiten abgebrochen ({2})", sourceId, count, e.getClass().getName());
        }
    }

    /**
     * Beendet den laufenden Lauf nach der aktuellen Seite, ohne zu unterbrechen (ein Interrupt mitten im Schreiben
     * schlösse die Dateikanäle des Lucene-Index); erst nach der Frist wird unterbrochen. Vor dem Hintergrund der
     * Anreicherungen schließen.
     */
    @Override
    public void close() throws InterruptedException {
        closed = true;
        runs.shutdown();
        if (!runs.awaitTermination(CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
            runs.shutdownNow();
        }
    }
}

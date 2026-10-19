package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.acquisition.AcquisitionScheduler;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Lädt die gefundenen Seiten einer Schlagwortsuche in den Cache (ADR 0013, wie {@code KnowledgePrefetchService} im
 * Enterprise AI Client): jede sichtbare Seite eines Stapels, deren Version nicht im Cache liegt, geht als Anfrage des
 * Vordergrunds an den {@link AcquisitionScheduler} und von dort an den Beschaffungs-Use-Case ({@link PageQueries#page},
 * mit den Rechten des Aufrufers); der legt sie ab, und Volltextindex und Anreicherungen folgen von selbst. Welche
 * Seiten das sind, wählt die Suche mit ihrer {@link CandidateSelectionStrategy} aus. Lädt der Hintergrund dieselbe
 * Seite gerade oder wartet sie dort, wird sie hochgestuft und nicht doppelt geladen (ADR 0016).
 *
 * <p>Technische Aufrufer (Desktop-App, Dienstkonto) warten höchstens {@code technicalWait}, Standard so lange wie
 * delegierte; mit {@link Duration#ZERO} kehrt die Suche sofort zurück, die Seiten laden im Hintergrund weiter, und
 * {@link #progress} zählt mit ({@link KeywordSearch.Progress}). Delegierte Benutzer laden nur während ihres Aufrufs:
 * ihre Anmeldung soll die Anfrage nicht überdauern, darum warten sie höchstens {@code wait}, und ihre noch wartenden
 * Abrufe entfallen danach; ein schon laufender Abruf endet regulär.
 */
public final class SearchHitPrefetch implements KeywordSearch.Prefetch, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SearchHitPrefetch.class.getName());

    /** So lange wartet die Suche höchstens auf die Seiten eines Stapels. */
    public static final Duration DEFAULT_WAIT = Duration.ofSeconds(5);

    private final Function<PageKey, Optional<PageMetadata>> cachedMetadata;
    private final Function<PageKey, Optional<String>> cachedVersion;
    private final AcquisitionScheduler scheduler;
    private final Duration wait;
    private final Duration technicalWait;
    private final AutoCloseable stop;
    /** Fortschritt der letzten Stapel, ältester zuerst verdrängt. */
    private final Map<String, Batch> batches = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Batch> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    /** So viele Stapel behält {@link #progress}. */
    static final int MAX_TRACKED = 256;

    /**
     * @param cachedVersion Version der Seite im Cache, leer, wenn sie dort fehlt
     * @param load          lädt eine Seite mit den Rechten des Aufrufers in den Cache, kehrt danach zurück
     * @param background    begrenzter Hintergrund, etwa {@link #background(int, int)}; {@link #close()} beendet ihn
     */
    public SearchHitPrefetch(Function<PageKey, Optional<String>> cachedVersion,
                             BiConsumer<AccessContext, PageKey> load, ExecutorService background) {
        this(cachedVersion, load, background, DEFAULT_WAIT);
    }

    /** @param wait so lange wartet die Suche höchstens auf die Seiten eines Stapels */
    public SearchHitPrefetch(Function<PageKey, Optional<String>> cachedVersion,
                             BiConsumer<AccessContext, PageKey> load, ExecutorService background, Duration wait) {
        this(cachedVersion, load, background, wait, wait);
    }

    /**
     * @param wait          so lange wartet die Suche delegierter Benutzer höchstens auf die Seiten eines Stapels
     * @param technicalWait so lange wartet die Suche technischer Aufrufer; {@link Duration#ZERO}: gar nicht
     */
    public SearchHitPrefetch(Function<PageKey, Optional<String>> cachedVersion,
                             BiConsumer<AccessContext, PageKey> load, ExecutorService background, Duration wait,
                             Duration technicalWait) {
        this(cachedVersion, key -> Optional.empty(), load, background, wait, technicalWait);
    }

    /**
     * Mit eigenem Planer auf {@code background}: so viele Abrufe zugleich, wie der Hintergrund Threads hat, ohne
     * Hintergrundfüllung; {@link #close()} beendet beide.
     *
     * @param cachedMetadata Kopfdaten der Seite im Cache, leer, wenn sie dort fehlt; damit braucht ein geladener Fund
     *                       keine zweite Rückfrage bei der Quelle
     */
    public SearchHitPrefetch(Function<PageKey, Optional<String>> cachedVersion,
                             Function<PageKey, Optional<PageMetadata>> cachedMetadata,
                             BiConsumer<AccessContext, PageKey> load, ExecutorService background, Duration wait,
                             Duration technicalWait) {
        this(cachedVersion, cachedMetadata, new AcquisitionScheduler(load, limits(background),
                Objects.requireNonNull(background, "background")), wait, technicalWait, true, background);
    }

    /**
     * Über einen gemeinsamen Planer, den auch die Hintergrundfüllung nutzt (ADR 0016); {@link #close()} schließt ihn
     * nicht, das tut sein Eigentümer.
     */
    public SearchHitPrefetch(Function<PageKey, Optional<String>> cachedVersion,
                             Function<PageKey, Optional<PageMetadata>> cachedMetadata, AcquisitionScheduler scheduler,
                             Duration wait, Duration technicalWait) {
        this(cachedVersion, cachedMetadata, scheduler, wait, technicalWait, false, null);
    }

    private SearchHitPrefetch(Function<PageKey, Optional<String>> cachedVersion,
                              Function<PageKey, Optional<PageMetadata>> cachedMetadata, AcquisitionScheduler scheduler,
                              Duration wait, Duration technicalWait, boolean owned, ExecutorService background) {
        this.cachedMetadata = Objects.requireNonNull(cachedMetadata, "cachedMetadata");
        this.cachedVersion = Objects.requireNonNull(cachedVersion, "cachedVersion");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.wait = Objects.requireNonNull(wait, "wait");
        this.technicalWait = Objects.requireNonNull(technicalWait, "technicalWait");
        if (owned) {
            AutoCloseable executor = stopping(background);
            this.stop = () -> {
                try {
                    scheduler.close();
                } finally {
                    executor.close();
                }
            };
        } else {
            this.stop = () -> { };
        }
    }

    private SearchHitPrefetch() {
        this.cachedMetadata = key -> Optional.empty();
        this.cachedVersion = key -> Optional.empty();
        this.scheduler = null;
        this.wait = Duration.ZERO;
        this.technicalWait = Duration.ZERO;
        this.stop = () -> { };
    }

    /** Grenzen für einen eigenen Planer: so viele zugleich, wie {@code background} Threads hat, kein Hintergrund. */
    private static AcquisitionScheduler.Limits limits(ExecutorService background) {
        if (background instanceof ThreadPoolExecutor pool) {
            int parallel = Math.max(1, pool.getMaximumPoolSize());
            return new AcquisitionScheduler.Limits(parallel, 0, parallel,
                    Math.max(1, pool.getQueue().remainingCapacity() + parallel));
        }
        AcquisitionScheduler.Limits defaults = AcquisitionScheduler.Limits.DEFAULT;
        return new AcquisitionScheduler.Limits(defaults.foreground(), 0, defaults.perSource(), defaults.capacity());
    }

    @Override
    public boolean current(KeywordSearch.Hit hit) {
        if (scheduler == null || hit.version() == null) {
            return false;
        }
        Optional<String> cached = cachedVersion.apply(new PageKey(hit.sourceId(), hit.pageId()));
        return cached.isPresent() && hit.version().equals(cached.get());
    }

    @Override
    public CandidateSelectionStrategy.Capacity capacity() {
        return scheduler == null ? CandidateSelectionStrategy.Capacity.DEFAULT
                : new CandidateSelectionStrategy.Capacity(scheduler.limits().foreground(),
                        scheduler.foregroundBacklog());
    }

    @Override
    public Fill prefetch(AccessContext caller, List<KeywordSearch.Hit> hits) {
        if (scheduler == null) {
            return Fill.NONE;
        }
        Map<KeywordSearch.Hit, AcquisitionScheduler.Ticket> waiting = new LinkedHashMap<>();
        List<KeywordSearch.Hit> pending = new ArrayList<>();
        Set<PageKey> seen = new HashSet<>();
        for (KeywordSearch.Hit hit : hits) {
            PageKey key = new PageKey(hit.sourceId(), hit.pageId());
            if (!seen.add(key) || current(hit)) {
                continue;
            }
            try {
                // Eine Seite, die schon wartet oder lädt, teilt sich den Abruf; ein Stapel bricht nur seine Anfrage ab.
                waiting.put(hit, scheduler.request(caller, key, AcquisitionScheduler.Priority.FOREGROUND));
            } catch (RejectedExecutionException e) {
                pending.add(hit);
            }
        }
        Batch batch = new Batch(UUID.randomUUID().toString(), waiting.size() + pending.size(),
                List.copyOf(waiting.values()));
        pending.forEach(hit -> batch.finished(false));
        waiting.values().forEach(ticket -> ticket.done().whenComplete((stored, failure) ->
                batch.finished(failure == null && Boolean.TRUE.equals(stored))));
        synchronized (batches) {
            batches.put(batch.id, batch);
        }
        boolean delegated = caller.credential() != null;
        long deadline = System.nanoTime() + (delegated ? wait : technicalWait).toNanos();
        int failed = 0;
        for (Map.Entry<KeywordSearch.Hit, AcquisitionScheduler.Ticket> entry : waiting.entrySet()) {
            AcquisitionScheduler.Ticket ticket = entry.getValue();
            CompletableFuture<Boolean> future = ticket.done();
            try {
                if (!future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    failed++;
                }
            } catch (TimeoutException e) {
                pending.add(entry.getKey());
                if (delegated) {
                    // Die Anmeldung des Benutzers bleibt in seinem Aufruf; ein laufender Abruf endet regulär.
                    ticket.cancel();
                }
            } catch (CancellationException e) {
                // Ein delegierter Benutzer hat seinen Abruf beendet; die Seite fehlt noch.
                pending.add(entry.getKey());
            } catch (ExecutionException e) {
                failed++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pending.add(entry.getKey());
            }
        }
        return new Fill(pending, failed, batch.id, batch.snapshot());
    }

    @Override
    public Optional<PageMetadata> cachedMetadata(PageKey key) {
        try {
            return cachedMetadata.apply(key);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean cancel(String id) {
        Batch batch;
        synchronized (batches) {
            batch = batches.get(id);
        }
        if (batch == null) {
            return false;
        }
        batch.cancel();
        return true;
    }

    @Override
    public Optional<KeywordSearch.Progress> progress(String id) {
        synchronized (batches) {
            Batch batch = batches.get(id);
            return batch == null ? Optional.empty() : Optional.of(batch.snapshot());
        }
    }

    /** Wie weit die Seiten eines Stapels geladen sind; zählt jede Seite genau einmal. */
    private static final class Batch {

        private final String id;
        private final int total;
        /** Die Anfragen dieses Stapels; nur sie zieht {@link #cancel()} zurück. */
        private final List<AcquisitionScheduler.Ticket> own;
        private int loaded;
        private int failed;
        private boolean cancelled;

        private Batch(String id, int total, List<AcquisitionScheduler.Ticket> own) {
            this.id = id;
            this.total = total;
            this.own = own;
        }

        /** Wartendes entfällt; ein schon laufender Abruf endet regulär. */
        private void cancel() {
            synchronized (this) {
                cancelled = true;
            }
            own.forEach(AcquisitionScheduler.Ticket::cancel);
        }

        private synchronized void finished(boolean stored) {
            if (loaded + failed < total) {
                if (stored) {
                    loaded++;
                } else {
                    failed++;
                }
            }
        }

        private synchronized KeywordSearch.Progress snapshot() {
            return new KeywordSearch.Progress(id, total, loaded, failed, cancelled);
        }
    }

    /** Lädt nichts, etwa ohne Cache. */
    public static SearchHitPrefetch disabled() {
        return new SearchHitPrefetch();
    }

    /**
     * Der Hintergrund: {@code parallel} Abrufe zugleich, höchstens {@code capacity} wartende; was nicht hineinpasst,
     * zählt als noch nicht geladen.
     */
    public static ExecutorService background(int parallel, int capacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(parallel, parallel, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity),
                Thread.ofVirtual().name("search-fill-", 0).factory(), (task, pool) -> {
                    throw new RejectedExecutionException("Laden der Suchtreffer ausgelastet");
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Beendet einen Hintergrund ohne Unterbrechen laufender Abrufe: Wartendes entfällt, der laufende Abruf endet
     * regulär. Ein Unterbrechen mitten im Schreiben schlösse die Dateikanäle der Lucene-Indizes und machte ihre
     * Sperren ungültig.
     */
    public static AutoCloseable stopping(ExecutorService background) {
        return () -> {
            if (background instanceof ThreadPoolExecutor pool) {
                pool.getQueue().clear();
            }
            background.shutdown();
            // Kein Unterbrechen: der laufende Abruf hat eigene Fristen und endet von selbst.
            while (!background.awaitTermination(30, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.INFO, "Laden der Suchtreffer endet noch; warte auf den laufenden Abruf");
            }
        };
    }

    @Override
    public void close() throws Exception {
        stop.close();
    }
}

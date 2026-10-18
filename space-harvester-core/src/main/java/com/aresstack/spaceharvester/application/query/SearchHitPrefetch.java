package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Lädt die gefundenen Seiten einer Schlagwortsuche in den Cache (ADR 0013, wie {@code KnowledgePrefetchService} im
 * Enterprise AI Client): jede sichtbare Seite eines Stapels, deren Version nicht im Cache liegt, geht an den
 * Beschaffungs-Use-Case ({@link PageQueries#page}, mit den Rechten des Aufrufers); der legt sie ab, und Volltextindex
 * und Anreicherungen folgen von selbst. Die Suche wartet höchstens {@link #DEFAULT_WAIT} darauf und sucht dann im
 * Cache.
 *
 * <p>Was danach noch lädt, lädt für technische Aufrufer weiter und erscheint mit dem nächsten Stapel. Delegierte
 * Benutzer laden nur während ihres Aufrufs: ihre Anmeldung soll die Anfrage nicht überdauern, darum entfallen ihre
 * noch wartenden Abrufe am Ende der Wartezeit; ein schon laufender Abruf endet regulär.
 */
public final class SearchHitPrefetch implements KeywordSearch.Prefetch, AutoCloseable {

    private static final System.Logger LOG = System.getLogger(SearchHitPrefetch.class.getName());

    /** So lange wartet die Suche höchstens auf die Seiten eines Stapels. */
    public static final Duration DEFAULT_WAIT = Duration.ofSeconds(5);

    private final Function<PageKey, Optional<String>> cachedVersion;
    private final BiConsumer<AccessContext, PageKey> load;
    private final ExecutorService background;
    private final Duration wait;
    private final AutoCloseable stop;
    private final Map<PageKey, Future<?>> running = new ConcurrentHashMap<>();

    /**
     * @param cachedVersion Version der Seite im Cache, leer, wenn sie dort fehlt
     * @param load          lädt eine Seite mit den Rechten des Aufrufers in den Cache, kehrt danach zurück
     * @param background    begrenzter Hintergrund, etwa {@link #background(int)}; {@link #close()} beendet ihn
     */
    public SearchHitPrefetch(Function<PageKey, Optional<String>> cachedVersion,
                             BiConsumer<AccessContext, PageKey> load, ExecutorService background) {
        this(cachedVersion, load, background, DEFAULT_WAIT);
    }

    /** @param wait so lange wartet die Suche höchstens auf die Seiten eines Stapels */
    public SearchHitPrefetch(Function<PageKey, Optional<String>> cachedVersion,
                             BiConsumer<AccessContext, PageKey> load, ExecutorService background, Duration wait) {
        this.cachedVersion = Objects.requireNonNull(cachedVersion, "cachedVersion");
        this.load = Objects.requireNonNull(load, "load");
        this.background = Objects.requireNonNull(background, "background");
        this.wait = Objects.requireNonNull(wait, "wait");
        this.stop = stopping(background);
    }

    private SearchHitPrefetch() {
        this.cachedVersion = key -> Optional.empty();
        this.load = (caller, key) -> { };
        this.background = null;
        this.wait = Duration.ZERO;
        this.stop = () -> { };
    }

    @Override
    public int prefetch(AccessContext caller, List<KeywordSearch.Hit> hits) {
        if (background == null) {
            return 0;
        }
        List<Future<?>> waiting = new ArrayList<>();
        List<Future<?>> own = new ArrayList<>();
        int rejected = 0;
        for (KeywordSearch.Hit hit : hits) {
            PageKey key = new PageKey(hit.sourceId(), hit.pageId());
            Optional<String> cached = cachedVersion.apply(key);
            if (cached.isPresent() && hit.version() != null && hit.version().equals(cached.get())) {
                continue;
            }
            Future<?> existing = running.get(key);
            if (existing != null) {
                waiting.add(existing);
                continue;
            }
            CompletableFuture<Void> done = new CompletableFuture<>();
            if (running.putIfAbsent(key, done) != null) {
                waiting.add(running.get(key));
                continue;
            }
            try {
                Future<?> task = background.submit(() -> {
                    try {
                        load.accept(caller, key);
                    } catch (RuntimeException e) {
                        // Ohne Seiten-ID; die Suche zeigt die Seite dann nicht.
                        LOG.log(System.Logger.Level.INFO, "Laden eines Suchtreffers in {0} gescheitert ({1})",
                                key.sourceId(), e.getClass().getName());
                    } finally {
                        running.remove(key, done);
                        done.complete(null);
                    }
                });
                done.whenComplete((ignored, failure) -> {
                    if (done.isCancelled()) {
                        task.cancel(false);
                        running.remove(key, done);
                    }
                });
                waiting.add(done);
                own.add(done);
            } catch (RejectedExecutionException e) {
                running.remove(key, done);
                rejected++;
            }
        }
        long deadline = System.nanoTime() + wait.toNanos();
        int pending = rejected;
        for (Future<?> future : waiting) {
            try {
                future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                pending++;
                if (caller.credential() != null && own.contains(future)) {
                    // Die Anmeldung des Benutzers bleibt in seinem Aufruf.
                    future.cancel(false);
                }
            } catch (CancellationException e) {
                // Ein delegierter Benutzer hat seinen Abruf beendet; die Seite fehlt noch.
                pending++;
            } catch (ExecutionException e) {
                // Das Laden fängt seine Fehler selbst.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pending++;
            }
        }
        return pending;
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

package com.aresstack.spaceharvester.application.query;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Vorladen der Schlagworttreffer (#180, wie {@code KnowledgePrefetchService} und {@code SearchDrivenIndexingUseCase}
 * im Enterprise AI Client): die ersten {@value #MAX_PER_BATCH} sichtbaren Treffer eines Stapels, deren Version nicht
 * im Cache liegt, gehen an den Beschaffungs-Use-Case im Hintergrund (etwa {@code PageRenewal}); der legt sie mit den
 * Rechten des Aufrufers im Cache ab, und Anreicherungen und Volltextindex folgen von selbst. Delegierte Benutzer
 * werden nicht vorgeladen: ihre Anmeldung soll die Anfrage nicht überdauern.
 */
public final class SearchHitPrefetch implements KeywordSearch.Prefetch, AutoCloseable {

    /** Höchstens so viele Treffer je Stapel. */
    public static final int MAX_PER_BATCH = 10;

    private final Function<PageKey, Optional<String>> cachedVersion;
    private final BiConsumer<AccessContext, PageKey> load;
    private final AutoCloseable background;

    /**
     * @param cachedVersion Version der Seite im Cache, leer, wenn sie dort fehlt
     * @param load          begrenztes Laden im Hintergrund, kehrt sofort zurück
     */
    public SearchHitPrefetch(Function<PageKey, Optional<String>> cachedVersion,
                             BiConsumer<AccessContext, PageKey> load) {
        this(cachedVersion, load, () -> { });
    }

    /** @param background was {@link #close()} beendet, etwa der Hintergrund hinter {@code load} */
    public SearchHitPrefetch(Function<PageKey, Optional<String>> cachedVersion,
                             BiConsumer<AccessContext, PageKey> load, AutoCloseable background) {
        this.cachedVersion = Objects.requireNonNull(cachedVersion, "cachedVersion");
        this.load = Objects.requireNonNull(load, "load");
        this.background = Objects.requireNonNull(background, "background");
    }

    @Override
    public void prefetch(AccessContext caller, List<KeywordSearch.Hit> hits) {
        if (caller.credential() != null) {
            return;
        }
        for (KeywordSearch.Hit hit : hits.subList(0, Math.min(hits.size(), MAX_PER_BATCH))) {
            PageKey key = new PageKey(hit.sourceId(), hit.pageId());
            Optional<String> cached = cachedVersion.apply(key);
            if (cached.isEmpty() || hit.version() == null || !hit.version().equals(cached.get())) {
                load.accept(caller, key);
            }
        }
    }

    /** Lädt nichts vor, etwa ohne Cache. */
    public static SearchHitPrefetch disabled() {
        return new SearchHitPrefetch(key -> Optional.empty(), (caller, key) -> { });
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
            if (!background.awaitTermination(30, TimeUnit.SECONDS)) {
                background.shutdownNow();
            }
        };
    }

    @Override
    public void close() throws Exception {
        background.close();
    }
}

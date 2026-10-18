package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.application.query.CacheFreshness;
import java.util.Objects;

/**
 * Suche über den Cache (ADR 0013), unter {@code space-harvester.search.*} und {@code space-harvester.cache.*}. Gilt
 * für jede neue Suchanfrage, laufende Suchen enden mit ihrem Stand.
 *
 * @param semantic        semantische Suche erlaubt (Haken „Semantische Suche“); Standard aus. Ohne Embedding-Modell
 *                        bleibt sie trotzdem aus, es gibt keine unerwünschte semantische Suche
 * @param rerank          Treffer über das Rerank-Modell umordnen, wenn eines eingestellt ist; kein RRF, keine Fusion
 *                        fremder Trefferlisten
 * @param prefetchHits    Treffer der nativen Quellensuche zuerst in den Cache laden (ADR 0013, Punkt 2)
 * @param cacheFreshness  wann eine Seite aus dem Cache ohne Rückfrage gilt; die Einstellungen zeigen sie als
 *                        {@link RefreshStrategy}
 */
public record SearchSettings(boolean semantic, boolean rerank, boolean prefetchHits, CacheFreshness cacheFreshness) {

    public static final String SEMANTIC = "search.semantic";
    public static final String RERANK = "search.rerank";
    public static final String PREFETCH_HITS = "search.prefetch-hits";
    public static final String CACHE_MAX_AGE = "cache.max-age";
    public static final String CACHE_VERSION_CHECK = "cache.version-check";
    public static final String CACHE_REFRESH_STRATEGY = "cache.refresh-strategy";

    /** Schlagwortsuche mit Beschaffung, semantisch aus, Reranking an, sobald ein Modell eingestellt ist. */
    public static final SearchSettings DEFAULT = new SearchSettings(false, true, true, CacheFreshness.DEFAULT);

    public SearchSettings {
        cacheFreshness = cacheFreshness == null ? CacheFreshness.DEFAULT : cacheFreshness;
    }

    /** Die gewählte Aktualisierungsstrategie. */
    public RefreshStrategy refreshStrategy() {
        return RefreshStrategy.of(cacheFreshness);
    }

    public SearchSettings withSemantic(boolean value) {
        return new SearchSettings(value, rerank, prefetchHits, cacheFreshness);
    }

    public SearchSettings withCacheFreshness(CacheFreshness value) {
        return new SearchSettings(semantic, rerank, prefetchHits, Objects.requireNonNull(value, "value"));
    }
}

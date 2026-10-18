package com.aresstack.spaceharvester.application.query.progressive;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import java.util.Objects;

/**
 * Eine Suchanfrage der progressiven Suche; im Harvester die {@link KeywordSearch} mit ihren Rechteprüfungen,
 * vorläufigen Treffern und dem Laden in den Cache (ADR 0016). Treffer, die der Aufrufer nicht sehen darf, liefert sie
 * nie, auch nicht als Zahl.
 */
public interface VariantSearch {

    /**
     * @throws RuntimeException wenn die Suche scheitert; der Orchestrator versucht es nach
     *                          {@link SearchExecutionPolicy#retries()} erneut
     */
    KeywordSearch.Results search(AccessContext caller, KeywordSearch.Query query, int limit);

    /** Bricht das Laden eines Stapels ab ({@link KeywordSearch#cancel(String)}). */
    default void cancelLoading(String progressId) {
    }

    static VariantSearch of(KeywordSearch keywords) {
        Objects.requireNonNull(keywords, "keywords");
        return new VariantSearch() {
            @Override
            public KeywordSearch.Results search(AccessContext caller, KeywordSearch.Query query, int limit) {
                return keywords.search(caller, query, null, limit);
            }

            @Override
            public void cancelLoading(String progressId) {
                keywords.cancel(progressId);
            }
        };
    }
}

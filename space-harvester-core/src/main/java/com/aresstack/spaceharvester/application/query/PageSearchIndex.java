package com.aresstack.spaceharvester.application.query;

import java.util.List;
import java.util.Optional;

/**
 * Lesender Port auf den Suchindex (#28), umgesetzt vom Lucene-Cache (#26) auf denselben Kopfdaten wie
 * {@link PageCacheReader}; in Tests von Fakes. Keine Lucene-, Spring- oder Confluence-Typen.
 *
 * <p>Der Index enthält nur bestätigte Commits: Suchen sehen nie einen unbestätigten Sync-Lauf. Die Rechteprüfung macht
 * der Aufrufer an jedem Treffer; der Index filtert nur nach der Anfrage. Alle Methoden dürfen
 * {@link CacheUnavailableException} werfen, wenn der Index nicht lesbar ist.
 */
public interface PageSearchIndex {

    /**
     * Die Indexgeneration, die Suchen auf diese Quelle jetzt lesen sollen.
     *
     * @return leer, wenn für die Quelle nichts im Index liegt
     */
    Optional<SearchIndexState> state(String sourceId);

    /**
     * Treffer einer Anfrage aus genau einer Indexgeneration, sortiert nach {@link PageSearchQuery#sort()} und den
     * Regeln von {@link SearchSort}, gefiltert nach {@link PageSearchQuery#matches}.
     *
     * @param generation    Indexgeneration aus {@link #state}
     * @param afterPosition nur Treffer hinter dem Treffer mit dieser {@link SearchHit#position()}; {@code null} ab dem
     *                      ersten
     * @param limit         höchstens so viele; weniger nur, wenn keine weiteren folgen
     * @throws CacheGenerationChangedException wenn die Generation nicht mehr lesbar ist
     */
    List<SearchHit> search(PageSearchQuery query, String generation, String afterPosition, int limit);

    /** Ein Index, der nicht eingerichtet ist: jede Suche scheitert mit {@link CacheUnavailableException}. */
    static PageSearchIndex unavailable() {
        return new PageSearchIndex() {
            @Override
            public Optional<SearchIndexState> state(String sourceId) {
                throw new CacheUnavailableException("Kein Suchindex eingerichtet");
            }

            @Override
            public List<SearchHit> search(PageSearchQuery query, String generation, String afterPosition,
                                          int limit) {
                throw new CacheUnavailableException("Kein Suchindex eingerichtet");
            }
        };
    }
}

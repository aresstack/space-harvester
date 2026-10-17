package com.aresstack.spaceharvester.application.query;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
     * Die Indexgeneration, die Suchen über diese Quellen jetzt lesen sollen (#114). Sie ändert sich, sobald sich die
     * Generation einer dieser Quellen ändert oder eine von ihnen in den Index kommt; Quellen außerhalb der Menge
     * beeinflussen sie nicht. Sie nennt die Quellen mit, auch wenn die Menge nur eine enthält: Eine globale Suche, deren
     * sichtbarer Umfang von einer Quelle zu einer anderen mit gleichem Stand wechselt, bekommt eine andere Generation.
     *
     * @param sourceIds nicht leer
     * @return leer, wenn für keine dieser Quellen etwas im Index liegt
     */
    Optional<SearchIndexState> state(Set<String> sourceIds);

    /**
     * Treffer einer Anfrage aus genau einer Indexgeneration und nur aus diesen Quellen, sortiert nach
     * {@link PageSearchQuery#sort()} und den Regeln von {@link SearchSort} über alle Quellen hinweg, gefiltert nach
     * {@link PageSearchQuery#matches}. Sortierung und Grenze gelten für die ganze Menge, nicht je Quelle.
     *
     * @param sourceIds     nicht leer; mit {@link PageSearchQuery#sourceId()} zusätzlich nur diese Quelle
     * @param generation    Indexgeneration aus {@link #state(Set)} für dieselben Quellen oder, bei genau einer Quelle,
     *                      aus {@link #state(String)}
     * @param afterPosition nur Treffer hinter dem Treffer mit dieser {@link SearchHit#position()}; {@code null} ab dem
     *                      ersten
     * @param limit         höchstens so viele; weniger nur, wenn keine weiteren folgen
     * @throws CacheGenerationChangedException wenn die Generation nicht mehr lesbar ist
     */
    List<SearchHit> search(PageSearchQuery query, Set<String> sourceIds, String generation, String afterPosition,
                           int limit);

    /**
     * Die Indexgeneration, die Suchen auf genau diese Quelle ({@code sourceId} gesetzt) jetzt lesen sollen; Umsetzungen
     * mit eigener Generation je Quelle überschreiben das.
     *
     * @return leer, wenn für die Quelle nichts im Index liegt
     */
    default Optional<SearchIndexState> state(String sourceId) {
        return state(Set.of(sourceId));
    }

    /** {@link #search(PageSearchQuery, Set, String, String, int)} über die Quelle der Anfrage. */
    default List<SearchHit> search(PageSearchQuery query, String generation, String afterPosition, int limit) {
        if (query.global()) {
            throw new IllegalArgumentException("Die Anfrage nennt keine Quelle");
        }
        return search(query, Set.of(query.sourceId()), generation, afterPosition, limit);
    }

    /** Ein Index, der nicht eingerichtet ist: jede Suche scheitert mit {@link CacheUnavailableException}. */
    static PageSearchIndex unavailable() {
        return new PageSearchIndex() {
            @Override
            public Optional<SearchIndexState> state(Set<String> sourceIds) {
                throw new CacheUnavailableException("Kein Suchindex eingerichtet");
            }

            @Override
            public List<SearchHit> search(PageSearchQuery query, Set<String> sourceIds, String generation,
                                          String afterPosition, int limit) {
                throw new CacheUnavailableException("Kein Suchindex eingerichtet");
            }
        };
    }
}
